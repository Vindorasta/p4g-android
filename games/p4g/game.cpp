
#include <nx/game.h>
#include <nx/runtime.h>
#include <cstdlib>

namespace nx {

const game::Info game::kInfo = {
    "p4g",
    "Persona 4 Golden",
    0x010062B01525C000ull,
    "io.github.p4grecomp",
    "private/p4g",
    0,
    "C433D980688380A6"
};

void game::after_init() {

    log("p4g: runtime initialized");
    log("p4g: beginning game-specific initialization");
}

void game::renderer_start() {
    log("p4g: renderer started");
}

void game::first_frame() {
    log("p4g: first frame presented");
}

} // namespace nx
