
#include <dirent.h>
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
// Importa o NSP/XCI do usuario e extrai os programas
// GXP do data.cpk.
//
// O tradutor Maxwell do P5R nao e compativel
// diretamente com os programas GXP do P4G.
//
// Fases:
// 0 = parado ou erro
// 1 = abrindo jogo
// 2 = extraindo shaders
// 3 = reservado para futura traducao GXP
// 4 = finalizando
// 5 = concluido
//
// A importacao permanece incompleta ate existir
// um tradutor GXP e integracao com o runtime.

namespace {

bool is_supported_title(uint64_t t) {
    uint64_t base = t & ~0xFFFull;
    return base == 0x010062B01525C000ull;
}

bool is_supported_build(const char* id) {
    const char* known[] = {
        "708BCACA873EA32B", // US 1.0.0
        "B78A03E489E5BD92", // EU 1.0.0
        "6E1F4CAE92A28C91", // JP 1.0.0
        "2F2253ACD6BE5D87", // Asia 1.0.0
    };

    for (const char* k : known) {
        if (std::strcmp(id, k) == 0) {
            return true;
        }
    }

    return false;
}

std::atomic<int> g_phase{0};
std::atomic<int> g_done{0};
std::atomic<int> g_total{0};

std::mutex g_error_lock;
std::string g_error;

void set_error(const std::string& e) {
    std::lock_guard<std::mutex> lock(g_error_lock);
    g_error = e;
}

std::string jstr(JNIEnv* env, jstring s) {
    if (!s) {
        return {};
    }

    const char* c = env->GetStringUTFChars(s, nullptr);

    std::string out = c ? c : "";

    if (c) {
        env->ReleaseStringUTFChars(s, c);
    }

    return out;
}

void remove_tree(const std::string& path) {
    if (DIR* d = opendir(path.c_str())) {
        while (dirent* e = readdir(d)) {
            std::string n = e->d_name;

            if (n == "." || n == "..") {
                continue;
            }

            std::string p = path + "/" + n;

            struct stat st{};

            if (lstat(p.c_str(), &st) == 0 &&
                S_ISDIR(st.st_mode)) {
                remove_tree(p);
            } else {
                unlink(p.c_str());
            }
        }

        closedir(d);
    }

    rmdir(path.c_str());
}

int fail(const std::string& e) {
    set_error(e);
    g_phase = 0;
    return 1;
}

int run(
    int nsp_fd,
    const std::string& nsp_name,
    const std::string& keys,
    const std::string& title_keys,
    const std::string& data_dir,
    const std::string& work_dir,
    int jobs
) {
    // Reservados para a futura etapa de conversao.
    (void)data_dir;
    (void)jobs;

    g_phase = 1;
    g_done = 0;
    g_total = 0;

    set_error("");

    // ------------------------------------------
    // ETAPA 1: Abrir NSP/XCI e validar o jogo
    // ------------------------------------------

    std::vector<nxi::InputFile> in;

    in.push_back({
        nsp_name,
        nsp_fd
    });

    int kfd = open(
        keys.c_str(),
        O_RDONLY | O_CLOEXEC
    );

    if (kfd < 0) {
        return fail("P4G: nao foi possivel ler prod.keys");
    }

    in.push_back({
        "prod.keys",
        kfd
    });

    int tfd = title_keys.empty()
        ? -1
        : open(
            title_keys.c_str(),
            O_RDONLY | O_CLOEXEC
        );

    if (tfd >= 0) {
        in.push_back({
            "title.keys",
            tfd
        });
    }

    std::string err;

    auto game = nxi::GameImage::open(
        in,
        0,
        err
    );

    close(kfd);

    if (tfd >= 0) {
        close(tfd);
    }

    if (!game) {
        return fail(err);
    }

    // Verificar Title ID do Persona 4 Golden.
    if (!is_supported_title(game->title_id())) {
        char title_id[32];

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

    // Verificar a existencia do executavel main.
    auto main_it = game->exefs().find("main");

    if (main_it == game->exefs().end() ||
        main_it->second.size() < 0x48) {
        return fail(
            "P4G: o arquivo do jogo nao possui main"
        );
    }

    // Identificar Build ID.
    char id[17] = {};

    for (int i = 0; i < 8; ++i) {
        std::snprintf(
            id + 2 * i,
            3,
            "%02X",
            main_it->second[0x40 + i]
        );
    }

    if (!is_supported_build(id)) {
        // Versoes desconhecidas nao sao bloqueadas
        // nesta fase experimental.
    }

    // ------------------------------------------
    // ETAPA 2: Extrair e validar shaders GXP
    // ------------------------------------------

    g_phase = 2;
    g_done = 0;
    g_total = 0;

    // Limpar apenas o diretorio de trabalho
    // desta tentativa de importacao.
    if (work_dir.empty() || work_dir == "/") {
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

    long blocks = p4g::extract_shaders(
        *game,
        extracted,
        [](int done, int total) {
            g_done = done;
            g_total = total;
        },
        err
    );

    if (blocks < 0) {
        return fail(
            err.empty()
                ? "P4G: falha ao extrair shaders GXP"
                : err
        );
    }

    if (blocks == 0) {
        return fail(
            "P4G: nenhum programa GXP foi extraido"
        );
    }

    // ------------------------------------------
    // ETAPA 3: Traducao GXP para Vulkan
    // ------------------------------------------
    //
    // IMPORTANTE:
    //
    // Nao chamar libspirvharness.so aqui.
    //
    // Essa biblioteca implementa a traducao
    // dos shaders Maxwell do P5R e espera
    // arquivos code.bin, ctrl.bin e manifests
    // especificos daquele formato.
    //
    // Os shaders do P4G estao no formato GXP.
    //
    // A traducao GXP para SPIR-V ainda precisa
    // ser implementada e integrada ao runtime.
    //
    // Os arquivos extraidos sao preservados
    // para diagnostico e desenvolvimento.
    //
    // Nao criar um diretorio spirv falso.
    // Nao indicar que o jogo esta pronto.

    g_done = static_cast<int>(blocks);
    g_total = static_cast<int>(blocks);

    return fail(
        "P4G: " +
        std::to_string(blocks) +
        " programas GXP extraidos e validados. "
        "Traducao GXP para Vulkan/SPIR-V "
        "ainda nao implementada. "
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
    int v[3] = {
        g_phase.load(),
        g_done.load(),
        g_total.load()
    };

    jintArray a = env->NewIntArray(3);

    if (a) {
        env->SetIntArrayRegion(
            a,
            0,
            3,
            v
        );
    }

    return a;
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
