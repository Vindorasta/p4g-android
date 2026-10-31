
#include "p4g_cpk.h"

#include <cstring>
#include <limits>
#include <vector>

namespace p4g {

namespace {

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
