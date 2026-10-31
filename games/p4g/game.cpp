
#include <nx/game.h>
#include <nx/runtime.h>

namespace nx {

const game::Info game::kInfo = {
    "p4g",
    "Persona 4 Golden",
    0x0000000000000000ull, // TODO: Title ID correto
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
