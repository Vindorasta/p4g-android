
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

// Etapa 61.18: preparar tabelas @UTF do CRIWARE.
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

    // Offsets relativos a base 8 do formato @UTF.
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

    // Limite preventivo para tabelas de metadados.
    constexpr uint64_t MAX_TABLE = 64ull * 1024 * 1024;

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

    // Proxima etapa: interpretar @UTF e localizar TOC.
    error = "CPK: cabecalho lido; parser TOC pendente";
    return false;
}

} // namespace p4g
