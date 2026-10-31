
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

    // Etapa 61.34: ler o primeiro arquivo do TOC.
    if (file_count == 0) {
        error = "P4G: TOC vazio";
        return false;
    }

    const size_t toc_row_start =
        8ull + read_be16(toc_data.data() + 10);

    const size_t toc_row_size =
        read_be16(toc_data.data() + 26);

    const uint64_t strings_offset =
        8ull + read_be32(toc_data.data() + 12);

    if (toc_row_start > toc_data.size() ||
        toc_row_size > toc_data.size() - toc_row_start) {
        error = "P4G: primeira linha TOC invalida";
        return false;
    }

    size_t cursor = toc_row_start;
    const size_t toc_row_end = toc_row_start + toc_row_size;

    std::string dir_name;
    std::string file_name;

    for (const auto& col : toc_columns) {
        const size_t n = utf_value_size(col.type);

        if (n == 0) {
            error = "P4G: tipo de coluna desconhecido";
            return false;
        }

        size_t value_pos = 0;

        if (col.storage == 0x50) {
            if (cursor > toc_row_end ||
                n > toc_row_end - cursor) {
                error = "P4G: linha TOC truncada";
                return false;
            }

            value_pos = cursor;
            cursor += n;

        } else if (col.storage == 0x30) {
            value_pos = col.constant_offset;

        } else {
            continue;
        }

        if (col.name != "DirName" &&
            col.name != "FileName") {
            continue;
        }

        if (col.type != 0xA ||
            value_pos > toc_data.size() ||
            4 > toc_data.size() - value_pos) {
            error = "P4G: string TOC invalida";
            return false;
        }

        const uint32_t str_offset =
            read_be32(toc_data.data() + value_pos);

        std::string value;

        if (!read_utf_string(
                toc_data,
                strings_offset,
                str_offset,
                value)) {
            error = "P4G: falha ao ler nome do TOC";
            return false;
        }

        if (col.name == "DirName") {
            dir_name = value;
        } else {
            file_name = value;
        }
    }

    error = "P4G: primeiro arquivo do TOC: " +
            dir_name + "/" + file_name +
            ". Leitura completa pendente.";

    return false;
}

} // namespace p4g
