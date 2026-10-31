
#include "p4g_shaders.h"

namespace p4g {

long extract_shaders(
    const nxi::GameImage& game,
    const std::string& out_dir,
    const std::function<void(int, int)>& progress,
    std::string& error
) {
    // Extrator de shaders do Persona 4 Golden.
    // Ainda precisamos identificar o formato
    // e a localizacao dos shaders no NSP.

    (void)game;
    (void)out_dir;

    if (progress) {
        progress(0, 1);
    }

    error = "P4G shader extraction not implemented yet";
    return -1;
}

} // namespace p4g
