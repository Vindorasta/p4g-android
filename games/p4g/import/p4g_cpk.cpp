
#include "p4g_cpk.h"

#include <cstring>
#include <limits>
#include <vector>
#include <map>

namespace p4g {

namespace {

struct UtfValue {
    int type = -1;
    uint64_t u = 0;
    std::string s;
};

using UtfRow = std::map<std::string, UtfValue>;

bool read_utf_string(
    const std::vector<uint8_t>& data,
    uint64_t strings_offset,
    uint32_t string_offset,
    std::string& result
) {
    result.clear();

    if (strings_offset > data.size() ||
        string_offset > data.size() - strings_offset) {
        return false;
    }

    size_t start =
        static_cast<size_t>(strings_offset + string_offset);

    size_t end = start;

    while (end < data.size() && data[end] != 0) {
        ++end;
    }

    if (end == data.size()) {
        return false;
    }

    result.assign(
        reinterpret_cast<const char*>(
            data.data() + start
        ),
        end - start
    );

    return true;
}

uint16_t read_be16(const uint8_t* p) {
    return (uint16_t(p[0]) << 8) |
           uint16_t(p[1]);
}

uint32_t read_be32(const uint8_t* p) {
    return (uint32_t(p[0]) << 24) |
           (uint32_t(p[1]) << 16) |
           (uint32_t(p[2]) << 8) |
           uint32_t(p[3]);
}

uint64_t read_be64(const uint8_t* p) {
    return (uint64_t(read_be32(p)) << 32) |
           uint64_t(read_be32(p + 4));
}

// Preparar tabelas @UTF do CRIWARE.
bool prepare_utf_table(
    std::vector<uint8_t>& data,
    std::string& error
) {
    if (data.size() < 32) {
        error = "CPK: tabela UTF muito pequena";
        return false;
    }

    // Algumas tabelas CRIWARE sao ofuscadas.
    if (std::memcmp(data.data(), "@UTF", 4) != 0) {
        uint32_t key = 0x655F;

        for (uint8_t& byte : data) {
            byte ^= static_cast<uint8_t>(key);
            key *= 0x4115;
        }
    }

    if (std::memcmp(data.data(), "@UTF", 4) != 0) {
        error = "CPK: assinatura UTF invalida";
        return false;
    }

    constexpr uint64_t base = 8;

    uint64_t rows =
        base + read_be16(data.data() + 10);

    uint64_t strings =
        base + read_be32(data.data() + 12);

    uint64_t binary =
        base + read_be32(data.data() + 16);

    uint16_t columns =
        read_be16(data.data() + 24);

    uint16_t row_size =
        read_be16(data.data() + 26);

    uint32_t row_count =
        read_be32(data.data() + 28);

    if (rows > data.size() ||
        strings > data.size() ||
        binary > data.size()) {
        error = "CPK: offsets UTF invalidos";
        return false;
    }

    if (columns == 0 ||
        (row_count != 0 && row_size == 0)) {
        error = "CPK: dimensoes UTF invalidas";
        return false;
    }

    uint64_t table_bytes =
        uint64_t(row_count) * row_size;

    if (table_bytes > data.size() - rows) {
        error = "CPK: linhas UTF fora dos limites";
        return false;
    }

    return true;
}

size_t utf_value_size(int type) {
    switch (type) {
        case 0:
        case 1:
            return 1;

        case 2:
        case 3:
            return 2;

        case 4:
        case 5:
        case 8:
        case 0xA:
            return 4;

        case 6:
        case 7:
        case 9:
        case 0xB:
            return 8;

        default:
            return 0;
    }
}

// Identificar as colunas @UTF.
struct UtfColumn {
    std::string name;
    int storage = 0;
    int type = 0;
    size_t constant_offset = 0;
};

bool read_utf_columns(
    const std::vector<uint8_t>& data,
    std::vector<UtfColumn>& columns,
    std::string& error
) {
    columns.clear();

    if (data.size() < 32 ||
        std::memcmp(data.data(), "@UTF", 4) != 0) {
        error = "CPK: tabela UTF invalida";
        return false;
    }

    const uint64_t rows_offset =
        8ull + read_be16(data.data() + 10);

    const uint64_t strings_offset =
        8ull + read_be32(data.data() + 12);

    const uint16_t count =
        read_be16(data.data() + 24);

    size_t pos = 0x20;

    if (rows_offset > data.size() ||
        strings_offset > data.size()) {
        error = "CPK: offsets UTF invalidos";
        return false;
    }

    for (uint16_t i = 0; i < count; ++i) {
        if (pos > rows_offset ||
            rows_offset - pos < 5) {
            error = "CPK: coluna UTF truncada";
            return false;
        }

        const uint8_t flags = data[pos];

        UtfColumn col;
        col.storage = flags & 0xF0;
        col.type = flags & 0x0F;

        const uint32_t name_offset =
            read_be32(data.data() + pos + 1);

        if (!read_utf_string(
                data,
                strings_offset,
                name_offset,
                col.name)) {
            error = "CPK: nome de coluna invalido";
            return false;
        }

        pos += 5;

        if (col.storage == 0x30) {
            const size_t size =
                utf_value_size(col.type);

            if (size == 0 ||
                size > rows_offset - pos) {
                error = "CPK: constante UTF invalida";
                return false;
            }

            col.constant_offset = pos;
            pos += size;

        } else if (col.storage != 0x10 &&
                   col.storage != 0x50) {
            error = "CPK: armazenamento UTF desconhecido";
            return false;
        }

        columns.push_back(col);
    }

    return true;
}

// Ler valores inteiros das colunas UTF.
bool read_utf_integer(
    const std::vector<uint8_t>& data,
    size_t pos,
    int type,
    uint64_t& value
) {
    value = 0;

    size_t size = 0;

    switch (type) {
        case 0:
        case 1:
            size = 1;
            break;

        case 2:
        case 3:
            size = 2;
            break;

        case 4:
        case 5:
            size = 4;
            break;

        case 6:
        case 7:
            size = 8;
            break;

        default:
            return false;
    }

    if (pos > data.size() ||
        size > data.size() - pos) {
        return false;
    }

    const uint8_t* p = data.data() + pos;

    switch (size) {
        case 1:
            value = p[0];
            break;

        case 2:
            value = read_be16(p);
            break;

        case 4:
            value = read_be32(p);
            break;

        case 8:
            value = read_be64(p);
            break;
    }

    return true;
}

uint64_t read_le64(const uint8_t* p) {
    uint64_t value = 0;

    for (int i = 0; i < 8; ++i) {
        value |= uint64_t(p[i]) << (i * 8);
    }

    return value;
}

// Adaptado da leitura de blocos CPK do P5R.
bool read_cpk_block(
    const nxi::GameImage& game,
    const nxi::GameImage::Entry& cpk,
    uint64_t offset,
    const char* magic,
    std::vector<uint8_t>& output,
    std::string& error
) {
    output.clear();

    if (offset > cpk.size ||
        cpk.size - offset < 16) {
        error = "CPK: cabecalho fora dos limites";
        return false;
    }

    uint8_t header[16] = {};

    if (game.read(cpk, offset, header, 16) != 16) {
        error = "CPK: falha ao ler cabecalho";
        return false;
    }

    if (std::memcmp(header, magic, 4) != 0) {
        error = "CPK: assinatura de bloco invalida";
        return false;
    }

    const uint64_t size = read_le64(header + 8);
    const uint64_t start = offset + 16;

    constexpr uint64_t MAX_TABLE =
        64ull * 1024 * 1024;

    if (size == 0 ||
        size > MAX_TABLE ||
        size > cpk.size - start ||
        size > std::numeric_limits<size_t>::max()) {
        error = "CPK: tamanho de tabela invalido";
        return false;
    }

    output.resize(static_cast<size_t>(size));

    if (game.read(
            cpk,
            start,
            output.data(),
            size
        ) != size) {
        output.clear();
        error = "CPK: falha ao ler tabela";
        return false;
    }

    return true;
}


// CRILAYLA: bitstream lido de tras para frente.
// O formato guarda 0x100 bytes de cabecalho sem compressao.
struct ReverseBits {
    const std::vector<uint8_t>& data;
    size_t next;
    uint8_t pool = 0;
    unsigned remaining = 0;

    ReverseBits(const std::vector<uint8_t>& bytes, size_t end)
        : data(bytes), next(end) {}

    bool get(unsigned count, uint32_t& value) {
        value = 0;
        for (unsigned i = 0; i < count; ++i) {
            if (remaining == 0) {
                if (next <= 16) return false;
                pool = data[--next];
                remaining = 8;
            }
            value = (value << 1) | ((pool >> (remaining - 1)) & 1u);
            --remaining;
        }
        return true;
    }
};

uint32_t read_le32(const uint8_t* p) {
    return uint32_t(p[0]) |
           (uint32_t(p[1]) << 8) |
           (uint32_t(p[2]) << 16) |
           (uint32_t(p[3]) << 24);
}

// Limitar memoria consumida por arquivos fornecidos pelo usuario.
constexpr uint64_t MAX_SHADER_BYTES = 128ull * 1024 * 1024;

bool unpack_crilayla(
    const std::vector<uint8_t>& input,
    uint64_t expected_size,
    std::vector<uint8_t>& output,
    std::string& error
) {
    output.clear();
    if (input.size() < 0x110 ||
        std::memcmp(input.data(), "CRILAYLA", 8) != 0) {
        error = "P4G: cabecalho CRILAYLA ausente";
        return false;
    }

    const uint64_t unpacked_body = read_le32(input.data() + 8);
    const uint64_t compressed_body = read_le32(input.data() + 12);
    const uint64_t total = unpacked_body + 0x100;
    if (total != expected_size || total > MAX_SHADER_BYTES ||
        compressed_body > input.size() - 0x110 ||
        compressed_body + 0x110 != input.size()) {
        error = "P4G: tamanhos CRILAYLA inconsistentes";
        return false;
    }

    output.resize(static_cast<size_t>(total));
    const size_t header_start = static_cast<size_t>(16 + compressed_body);
    std::memcpy(output.data(), input.data() + header_start, 0x100);

    ReverseBits bits(input, header_start);
    size_t write = output.size();
    const unsigned widths[] = {2, 3, 5, 8};

    while (write > 0x100) {
        uint32_t flag = 0;
        if (!bits.get(1, flag)) {
            error = "P4G: fluxo CRILAYLA truncado";
            return false;
        }
        if (!flag) {
            uint32_t literal = 0;
            if (!bits.get(8, literal)) {
                error = "P4G: literal CRILAYLA truncado";
                return false;
            }
            output[--write] = static_cast<uint8_t>(literal);
            continue;
        }

        uint32_t distance_bits = 0;
        if (!bits.get(13, distance_bits)) {
            error = "P4G: referencia CRILAYLA truncada";
            return false;
        }
        const size_t distance = static_cast<size_t>(distance_bits) + 3;
        uint64_t length = 3;
        bool extend = true;
        for (unsigned width : widths) {
            uint32_t part = 0;
            if (!bits.get(width, part)) {
                error = "P4G: comprimento CRILAYLA truncado";
                return false;
            }
            length += part;
            if (part != ((1u << width) - 1u)) {
                extend = false;
                break;
            }
        }
        if (extend) {
            uint32_t part = 0;
            do {
                if (!bits.get(8, part)) {
                    error = "P4G: extensao CRILAYLA truncada";
                    return false;
                }
                length += part;
                if (length > output.size()) {
                    error = "P4G: comprimento CRILAYLA invalido";
                    return false;
                }
            } while (part == 255);
        }

        if (length > write - 0x100 ||
            distance > output.size() - write) {
            error = "P4G: referencia CRILAYLA fora dos limites";
            return false;
        }
        for (uint64_t j = 0; j < length; ++j) {
            const size_t dest = --write;
            const size_t source = dest + distance;
            if (source >= output.size()) {
                error = "P4G: origem CRILAYLA invalida";
                return false;
            }
            output[dest] = output[source];
        }
    }
    return true;
}

size_t count_gxp_markers(const std::vector<uint8_t>& bytes) {
    size_t count = 0;
    for (size_t i = 0; i + 3 <= bytes.size(); ++i) {
        if (std::memcmp(bytes.data() + i, "GXP", 3) == 0) ++count;
    }
    return count;
}

} // namespace

bool list_cpk_files(
    const nxi::GameImage& game,
    const nxi::GameImage::Entry& cpk,
    std::vector<CpkFile>& files,
    std::string& error
) {
    files.clear();
    error.clear();

    std::vector<uint8_t> header;

    if (!read_cpk_block(
            game, cpk, 0, "CPK ",
            header, error)) {
        return false;
    }

    if (!prepare_utf_table(header, error)) {
        return false;
    }

    std::vector<UtfColumn> columns;

    if (!read_utf_columns(
            header, columns, error)) {
        return false;
    }

    if (columns.empty()) {
        error = "P4G: nenhuma coluna UTF encontrada";
        return false;
    }

    // Ler a primeira linha UTF.
    const size_t row_offset =
        8ull + read_be16(header.data() + 10);

    const size_t row_size =
        read_be16(header.data() + 26);

    const uint32_t row_count =
        read_be32(header.data() + 28);

    if (row_count == 0 ||
        row_offset > header.size() ||
        row_size > header.size() - row_offset) {
        error = "CPK: primeira linha UTF invalida";
        return false;
    }

    size_t pos = row_offset;
    const size_t row_end = row_offset + row_size;

    uint64_t toc = 0;
    uint64_t content = 0;

    bool has_toc = false;
    bool has_content = false;

    for (const auto& col : columns) {
        const size_t n = utf_value_size(col.type);

        if (n == 0) {
            error = "CPK: tipo UTF desconhecido";
            return false;
        }

        size_t value_pos = 0;

        if (col.storage == 0x50) {
            if (pos > row_end ||
                n > row_end - pos) {
                error = "CPK: valor UTF fora da linha";
                return false;
            }

            value_pos = pos;
            pos += n;

        } else if (col.storage == 0x30) {
            value_pos = col.constant_offset;

        } else {
            // Colunas sem valor armazenado.
            continue;
        }

        if (col.name != "TocOffset" &&
            col.name != "ContentOffset") {
            continue;
        }

        uint64_t value = 0;

        if (!read_utf_integer(
                header,
                value_pos,
                col.type,
                value)) {
            error = "CPK: offset UTF invalido";
            return false;
        }

        if (col.name == "TocOffset") {
            toc = value;
            has_toc = true;
        } else {
            content = value;
            has_content = true;
        }
    }

    if (!has_toc || !has_content) {
        error = "CPK: colunas de offset ausentes";
        return false;
    }

    if (toc >= cpk.size ||
        content >= cpk.size) {
        error = "CPK: offsets fora de data.cpk";
        return false;
    }

    // Ler o indice TOC.
    std::vector<uint8_t> toc_data;

    if (!read_cpk_block(
            game,
            cpk,
            toc,
            "TOC ",
            toc_data,
            error)) {
        return false;
    }

    // Validar a tabela @UTF do TOC.
    if (!prepare_utf_table(toc_data, error)) {
        return false;
    }

    // Identificar as colunas do TOC.
    std::vector<UtfColumn> toc_columns;

    if (!read_utf_columns(
            toc_data,
            toc_columns,
            error)) {
        return false;
    }

    if (toc_columns.empty()) {
        error = "P4G: nenhuma coluna encontrada no TOC";
        return false;
    }

    const uint32_t file_count =
        read_be32(toc_data.data() + 28);

    // Etapas 61.36-61.40: percorrer o TOC e verificar os seis shaders.
    if (file_count == 0) {
        error = "P4G: TOC vazio";
        return false;
    }

    const uint64_t toc_row_start =
        8ull + read_be16(toc_data.data() + 10);
    const uint64_t toc_row_size =
        read_be16(toc_data.data() + 26);
    const uint64_t strings_offset =
        8ull + read_be32(toc_data.data() + 12);

    if (toc_row_start > toc_data.size() ||
        toc_row_size == 0 ||
        uint64_t(file_count) >
            (toc_data.size() - toc_row_start) / toc_row_size) {
        error = "P4G: linhas do TOC fora dos limites";
        return false;
    }

    // Os deslocamentos de arquivos CRI CPK sao relativos ao menor
    // entre TocOffset e ContentOffset, conforme a convencao CPK.
    const uint64_t file_base = (toc < content) ? toc : content;

    const char* targets[] = {
        "output_cnv_v_standard.bin",
        "output_cnv_f_standard.bin",
        "output_cnv_v_effect.bin",
        "output_cnv_f_effect.bin",
        "output_cnv_v_sprite.bin",
        "output_cnv_f_sprite.bin"
    };
    constexpr size_t target_count = sizeof(targets) / sizeof(targets[0]);
    bool found[target_count] = {};
    size_t found_count = 0;
    size_t checked_count = 0;
    size_t compressed_count = 0;
    size_t crilayla_count = 0;
    size_t unpacked_count = 0;
    size_t gxp_count = 0;
    std::string first_file;
    std::string detail;

    for (uint32_t row = 0; row < file_count; ++row) {
        const size_t row_start = static_cast<size_t>(
            toc_row_start + uint64_t(row) * toc_row_size);
        const size_t row_end = row_start + static_cast<size_t>(toc_row_size);
        size_t cursor = row_start;

        std::string dir_name;
        std::string file_name;
        uint64_t file_offset = 0;
        uint64_t file_size = 0;
        uint64_t extract_size = 0;
        bool has_offset = false;
        bool has_size = false;
        bool has_extract_size = false;
        bool has_name = false;

        for (const auto& col : toc_columns) {
            const size_t n = utf_value_size(col.type);
            if (n == 0) {
                error = "P4G: tipo de coluna TOC desconhecido";
                return false;
            }

            size_t value_pos = 0;
            if (col.storage == 0x50) {
                if (cursor > row_end || n > row_end - cursor) {
                    error = "P4G: linha TOC truncada: " + std::to_string(row);
                    return false;
                }
                value_pos = cursor;
                cursor += n;
            } else if (col.storage == 0x30) {
                value_pos = col.constant_offset;
            } else if (col.storage == 0x10) {
                continue;
            } else {
                error = "P4G: armazenamento TOC desconhecido";
                return false;
            }

            if (col.name == "DirName" || col.name == "FileName") {
                if (col.type != 0xA ||
                    value_pos > toc_data.size() ||
                    4 > toc_data.size() - value_pos) {
                    error = "P4G: campo de nome invalido no TOC";
                    return false;
                }
                const uint32_t str_offset =
                    read_be32(toc_data.data() + value_pos);
                std::string value;
                if (!read_utf_string(
                        toc_data, strings_offset, str_offset, value)) {
                    error = "P4G: string invalida na linha " +
                            std::to_string(row);
                    return false;
                }
                if (col.name == "DirName") {
                    dir_name = value;
                } else {
                    file_name = value;
                    has_name = true;
                }
            } else if (col.name == "FileOffset" ||
                       col.name == "FileSize" ||
                       col.name == "ExtractSize") {
                uint64_t value = 0;
                if (!read_utf_integer(
                        toc_data, value_pos, col.type, value)) {
                    error = "P4G: valor numerico invalido na linha " +
                            std::to_string(row);
                    return false;
                }
                if (col.name == "FileOffset") {
                    file_offset = value;
                    has_offset = true;
                } else if (col.name == "FileSize") {
                    file_size = value;
                    has_size = true;
                } else {
                    extract_size = value;
                    has_extract_size = true;
                }
            }
        }

        if (row == 0) {
            first_file = dir_name + "/" + file_name;
        }

        // Para este teste, apenas os seis pacotes de shader sao lidos.
        if (!has_name) {
            continue;
        }
        size_t target = target_count;
        for (size_t i = 0; i < target_count; ++i) {
            if (file_name == targets[i] &&
                (dir_name == "shader" ||
                 dir_name == "/shader" ||
                 dir_name == "shader/")) {
                target = i;
                break;
            }
        }
        if (target == target_count || found[target]) {
            continue;
        }
        if (!has_offset || !has_size || !has_extract_size) {
            error = "P4G: metadados incompletos: " + file_name;
            return false;
        }
        if (file_offset > cpk.size - file_base ||
            file_size > cpk.size - file_base - file_offset) {
            error = "P4G: offset/tamanho fora do CPK: " + file_name;
            return false;
        }

        // Ler os dados completos apenas dos seis pacotes procurados.
        if (file_size == 0 || file_size > MAX_SHADER_BYTES ||
            extract_size == 0 || extract_size > MAX_SHADER_BYTES) {
            error = "P4G: pacote grande demais ou vazio: " + file_name;
            return false;
        }
        std::vector<uint8_t> packed(static_cast<size_t>(file_size));
        if (game.read(cpk, file_base + file_offset,
                      packed.data(), file_size) != file_size) {
            error = "P4G: falha ao ler pacote: " + file_name;
            return false;
        }

        found[target] = true;
        ++found_count;
        ++checked_count;
        const bool compressed = extract_size > file_size;
        if (compressed) ++compressed_count;

        const bool is_crilayla = packed.size() >= 8 &&
            std::memcmp(packed.data(), "CRILAYLA", 8) == 0;
        if (is_crilayla) ++crilayla_count;
        if (compressed && !is_crilayla) {
            error = "P4G: compressao desconhecida: " + file_name;
            return false;
        }
        if (!compressed && extract_size != file_size) {
            error = "P4G: tamanhos inconsistentes: " + file_name;
            return false;
        }

        std::vector<uint8_t> unpacked;
        if (compressed) {
            if (!unpack_crilayla(packed, extract_size, unpacked, error)) {
                error += " (" + file_name + ")";
                return false;
            }
        } else {
            unpacked.swap(packed);
        }
        if (unpacked.size() != extract_size) {
            error = "P4G: tamanho descomprimido invalido: " + file_name;
            return false;
        }
        ++unpacked_count;
        const size_t markers = count_gxp_markers(unpacked);
        gxp_count += markers;
        if (detail.size() < 240) {
            if (!detail.empty()) detail += ", ";
            detail += file_name;
            detail += "(GXP=" + std::to_string(markers) + ")";
        }

    }

    error = "P4G: TOC=" + std::to_string(file_count) +
            ", shaders=" + std::to_string(found_count) + "/6" +
            ", leitura=" + std::to_string(checked_count) + "/6" +
            ", comprimidos=" + std::to_string(compressed_count) +
            ", CRILAYLA=" + std::to_string(crilayla_count) +
            ", descomprimidos=" + std::to_string(unpacked_count) + "/6" +
            ", marcadores GXP=" + std::to_string(gxp_count) + ".";
    if (found_count != target_count) {
        error += " Ausentes:";
        for (size_t i = 0; i < target_count; ++i) {
            if (!found[i]) {
                error += " ";
                error += targets[i];
            }
        }
    } else {
        error += " Validacao de formato GXP e gravacao pendentes.";
    }

    // Continua retornando false: o importador de shaders ainda nao
    // esta implementado. Este passo e apenas um diagnostico.
    return false;
}

} // namespace p4g
