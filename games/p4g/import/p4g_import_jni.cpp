
#include <dirent.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <jni.h>
#include <sys/stat.h>
#include <unistd.h>

#include <atomic>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "nxgame.h"
#include "p4g_shaders.h"

// Persona 4 Golden Android
//
// Etapa 62.06:
// - Abrir NSP/XCI
// - Extrair programas GXP
// - Carregar libp4gshader.so
// - Verificar a interface C do Vita3K
//
// A conversao para SPIR-V sera ativada somente
// quando houver uma API que devolva o resultado
// e permita verificar sua validade.

namespace {

constexpr uint64_t P4G_TITLE_ID =
    0x010062B01525C000ull;

constexpr int SHADER_API_VERSION = 1;

std::atomic<int> g_phase{0};
std::atomic<int> g_done{0};
std::atomic<int> g_total{0};

std::mutex g_error_lock;
std::string g_error;

void set_error(const std::string& value) {
    std::lock_guard<std::mutex> lock(g_error_lock);
    g_error = value;
}

int fail(const std::string& message) {
    set_error(message);
    g_phase = 0;
    return 1;
}

std::string jstr(JNIEnv* env, jstring value) {
    if (!value) {
        return {};
    }

    const char* chars =
        env->GetStringUTFChars(value, nullptr);

    if (!chars) {
        return {};
    }

    std::string result(chars);

    env->ReleaseStringUTFChars(value, chars);

    return result;
}

bool is_supported_title(uint64_t title_id) {
    return (title_id & ~0xFFFull) == P4G_TITLE_ID;
}

bool is_supported_build(const char* build_id) {
    const char* known[] = {
        "708BCACA873EA32B",
        "B78A03E489E5BD92",
        "6E1F4CAE92A28C91",
        "2F2253ACD6BE5D87"
    };

    for (const char* id : known) {
        if (std::strcmp(build_id, id) == 0) {
            return true;
        }
    }

    return false;
}

void remove_tree(const std::string& path) {
    struct stat root{};

    if (lstat(path.c_str(), &root) != 0) {
        return;
    }

    if (!S_ISDIR(root.st_mode)) {
        unlink(path.c_str());
        return;
    }

    DIR* directory = opendir(path.c_str());

    if (!directory) {
        return;
    }

    while (dirent* entry = readdir(directory)) {
        std::string name = entry->d_name;

        if (name == "." || name == "..") {
            continue;
        }

        std::string child = path + "/" + name;

        struct stat info{};

        if (lstat(child.c_str(), &info) != 0) {
            continue;
        }

        if (S_ISDIR(info.st_mode)) {
            remove_tree(child);
        } else {
            unlink(child.c_str());
        }
    }

    closedir(directory);
    rmdir(path.c_str());
}

// --------------------------------------------------
// Interface dinamica do recompilador Vita3K
// --------------------------------------------------

using ShaderApiVersion = int (*)();

struct ShaderLibrary {
    void* handle = nullptr;
    ShaderApiVersion version = nullptr;

    ~ShaderLibrary() {
        if (handle) {
            dlclose(handle);
        }
    }

    ShaderLibrary() = default;

    ShaderLibrary(const ShaderLibrary&) = delete;
    ShaderLibrary& operator=(const ShaderLibrary&) =
        delete;
};

bool load_shader_library(
    ShaderLibrary& library,
    std::string& error
) {
    error.clear();

    dlerror();

    library.handle = dlopen(
        "libp4gshader.so",
        RTLD_NOW | RTLD_LOCAL
    );

    if (!library.handle) {
        const char* message = dlerror();

        error =
            "P4G: falha ao carregar "
            "libp4gshader.so: ";

        error += message ? message : "erro desconhecido";

        return false;
    }

    dlerror();

    void* symbol = dlsym(
        library.handle,
        "p4g_shader_api_version"
    );

    const char* lookup_error = dlerror();

    if (lookup_error || !symbol) {
        error =
            "P4G: interface p4g_shader_api_version "
            "nao encontrada: ";

        error += lookup_error
            ? lookup_error
            : "simbolo ausente";

        return false;
    }

    library.version =
        reinterpret_cast<ShaderApiVersion>(symbol);

    const int version = library.version();

    if (version != SHADER_API_VERSION) {
        error =
            "P4G: versao incompativel "
            "da API de shaders. Esperada: " +
            std::to_string(SHADER_API_VERSION) +
            ", encontrada: " +
            std::to_string(version);

        return false;
    }

    return true;
}

// --------------------------------------------------
// Importacao do Persona 4 Golden
// --------------------------------------------------

int run(
    int nsp_fd,
    const std::string& nsp_name,
    const std::string& keys,
    const std::string& title_keys,
    const std::string& data_dir,
    const std::string& work_dir,
    int jobs
) {
    // Parametros reservados para o processamento
    // e cache dos shaders em etapas posteriores.
    (void)data_dir;
    (void)jobs;

    g_phase = 1;
    g_done = 0;
    g_total = 0;

    set_error("");

    if (nsp_fd < 0) {
        return fail(
            "P4G: descritor NSP/XCI invalido"
        );
    }

    // ETAPA 1: abrir o jogo.

    std::vector<nxi::InputFile> inputs;

    inputs.push_back({
        nsp_name,
        nsp_fd
    });

    int keys_fd = open(
        keys.c_str(),
        O_RDONLY | O_CLOEXEC
    );

    if (keys_fd < 0) {
        return fail(
            "P4G: nao foi possivel ler prod.keys"
        );
    }

    inputs.push_back({
        "prod.keys",
        keys_fd
    });

    int title_fd = -1;

    if (!title_keys.empty()) {
        title_fd = open(
            title_keys.c_str(),
            O_RDONLY | O_CLOEXEC
        );
    }

    if (title_fd >= 0) {
        inputs.push_back({
            "title.keys",
            title_fd
        });
    }

    std::string error;

    auto game = nxi::GameImage::open(
        inputs,
        0,
        error
    );

    close(keys_fd);

    if (title_fd >= 0) {
        close(title_fd);
    }

    if (!game) {
        return fail(
            error.empty()
                ? "P4G: falha ao abrir NSP/XCI"
                : error
        );
    }

    if (!is_supported_title(game->title_id())) {
        char title_id[32] = {};

        std::snprintf(
            title_id,
            sizeof(title_id),
            "%016llX",
            static_cast<unsigned long long>(
                game->title_id()
            )
        );

        return fail(
            std::string("Unsupported Title ID: ") +
            title_id +
            " (expected Persona 4 Golden: "
            "010062B01525C000)"
        );
    }

    auto main_it = game->exefs().find("main");

    if (main_it == game->exefs().end() ||
        main_it->second.size() < 0x48) {
        return fail(
            "P4G: executavel main ausente "
            "ou invalido"
        );
    }

    char build_id[17] = {};

    for (int i = 0; i < 8; ++i) {
        std::snprintf(
            build_id + i * 2,
            3,
            "%02X",
            main_it->second[0x40 + i]
        );
    }

    const bool known_build =
        is_supported_build(build_id);

    // Builds desconhecidos permanecem permitidos
    // nesta fase experimental.
    (void)known_build;

    // ETAPA 2: extrair os shaders GXP.

    g_phase = 2;
    g_done = 0;
    g_total = 0;

    if (work_dir.empty() ||
        work_dir == "/" ||
        work_dir == "." ||
        work_dir == ".." ||
        work_dir[0] != '/') {
        return fail(
            "P4G: diretorio de trabalho invalido"
        );
    }

    remove_tree(work_dir);

    if (mkdir(work_dir.c_str(), 0700) != 0) {
        return fail(
            "P4G: nao foi possivel criar "
            "o diretorio de trabalho"
        );
    }

    const std::string extracted =
        work_dir + "/shaders";

    const long blocks = p4g::extract_shaders(
        *game,
        extracted,
        [](int done, int total) {
            g_done = done;
            g_total = total;
        },
        error
    );

    if (blocks < 0) {
        return fail(
            error.empty()
                ? "P4G: falha ao extrair shaders GXP"
                : error
        );
    }

    if (blocks == 0) {
        return fail(
            "P4G: nenhum programa GXP extraido"
        );
    }

    // ETAPA 3: verificar a biblioteca ARM64.

    g_phase = 3;
    g_done = 0;
    g_total = 1;

    ShaderLibrary shader_library;

    if (!load_shader_library(
            shader_library,
            error
        )) {
        return fail(error);
    }

    g_done = 1;

    // A biblioteca foi carregada e a versao
    // de sua interface foi confirmada.
    //
    // Ainda nao houve conversao GXP -> SPIR-V.
    // Nao declarar importacao concluida.

    return fail(
        "P4G: " +
        std::to_string(blocks) +
        " programas GXP extraidos. "
        "libp4gshader.so carregada com sucesso. "
        "API Vita3K v" +
        std::to_string(SHADER_API_VERSION) +
        " confirmada. "
        "Teste de traducao GXP para SPIR-V "
        "ainda pendente. "
        "Arquivos em: " +
        extracted
    );
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_io_github_p4grecomp_Importer_run(
    JNIEnv* env,
    jclass,
    jint nsp_fd,
    jstring nsp_name,
    jstring keys,
    jstring title_keys,
    jstring data_dir,
    jstring work_dir,
    jint jobs
) {
    return run(
        nsp_fd,
        jstr(env, nsp_name),
        jstr(env, keys),
        jstr(env, title_keys),
        jstr(env, data_dir),
        jstr(env, work_dir),
        jobs
    );
}

JNIEXPORT jintArray JNICALL
Java_io_github_p4grecomp_Importer_progress(
    JNIEnv* env,
    jclass
) {
    jint values[3] = {
        g_phase.load(),
        g_done.load(),
        g_total.load()
    };

    jintArray result = env->NewIntArray(3);

    if (result) {
        env->SetIntArrayRegion(
            result,
            0,
            3,
            values
        );
    }

    return result;
}

JNIEXPORT jstring JNICALL
Java_io_github_p4grecomp_Importer_error(
    JNIEnv* env,
    jclass
) {
    std::lock_guard<std::mutex> lock(g_error_lock);

    return env->NewStringUTF(
        g_error.c_str()
    );
}

} // extern "C"
