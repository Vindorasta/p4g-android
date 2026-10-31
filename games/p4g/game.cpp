
#include <nx/game.h>
#include <nx/runtime.h>

namespace nx {

const game::Info game::kInfo = {
    "p4g",
    "Persona 4 Golden",
    0x010062B01525C000ull, // Persona 4 Golden
    "io.github.p4grecomp",
    "private/p4g",
    0,
    "" // TODO: Build ID correto
};

void game::after_init() {
    log("p4g: runtime initialized");
}

void game::renderer_start() {
    log("p4g: renderer started");
}

void game::first_frame() {
    log("p4g: first frame");
}

} // namespace nx
