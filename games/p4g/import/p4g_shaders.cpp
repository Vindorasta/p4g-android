
#include "p4g_shaders.h"
#include "p4g_gxp.h"

#include <cstdint>
#include <string>

namespace p4g {

long extract_shaders(
    const nxi::GameImage& game,
    const std::string& out_dir,
    const std::function<void(int, int)>& progress,
    std::string& error
) {
    (void)out_dir;

    if (progress) {
        progress(0, 1);
    }

    // Localizar data.cpk na raiz do RomFS.
    const auto* cpk = game.find("data.cpk");

    if (!cpk || cpk->dir) {
        error = "P4G: data.cpk nao encontrado no RomFS";
        return -1;
    }

    if (cpk->size < 4) {
        error = "P4G: data.cpk muito pequeno";
        return -1;
    }

    // Ler a assinatura do arquivo CPK.
    uint8_t magic[4] = {};

    if (game.read(*cpk, 0, magic, 4) != 4) {
        error = "P4G: falha ao ler data.cpk";
        return -1;
    }

    if (magic[0] != 'C' ||
        magic[1] != 'P' ||
        magic[2] != 'K' ||
        magic[3] != ' ') {
        error = "P4G: assinatura CPK invalida";
        return -1;
    }

    // Diagnostico temporario.
    error = "P4G: data.cpk encontrado! "
            "Cabecalho CPK valido. "
            "Leitura interna ainda nao implementada.";

    return -1;
}

} // namespace p4g
