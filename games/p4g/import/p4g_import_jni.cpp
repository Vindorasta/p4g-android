
#include <dirent.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <jni.h>
#include <sys/stat.h>
#include <unistd.h>

#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <limits>
#include <mutex>
#include <string>
#include <vector>

#include "nxgame.h"
#include "p4g_shaders.h"

// Persona 4 Golden Android
// Etapa 62.09 - conversao em lote GXP -> SPIR-V.
//
// Fases:
// 0 = parado ou erro
// 1 = abrindo jogo
// 2 = extraindo GXP
// 3 = traduzindo GXP para SPIR-V
// 4 = gravando relatorio
// 5 = preparacao concluida
//
// A fase 5 indica somente que a preparacao
// de shaders foi concluida, nao que o jogo
// ja pode ser executado.

namespace {

constexpr uint64_t P4G_TITLE_ID =
    0x010062B01525C000ull;

constexpr int SHADER_API_VERSION = 2;

constexpr size_t MAX_GXP_SIZE =
    32u * 1024u * 1024u;

constexpr uint64_t MAX_SPIRV_SIZE =
    32ull * 1024ull * 1024ull;

std::atomic<int> g_phase{0};
std::atomic<int> g_done{0};
std::atomic<int> g_total{0};

std::mutex g_error_lock;
std::string g_error;

using ShaderVersion = int (*)();

using ShaderConvert = int (*)(
    const void*,
    size_t,
    const char*
);

struct ShaderLibrary {
    void* handle = nullptr;
    ShaderVersion version = nullptr;
    ShaderConvert convert = nullptr;

    ~ShaderLibrary() {
        if (handle) {
            dlclose(handle);
        }
    }

    ShaderLibrary() = default;
    ShaderLibrary(const ShaderLibrary&) = delete;
    ShaderLibrary& operator=(const ShaderLibrary&) = delete;
};

struct GxpEntry {
    std::string pack;
    std::string name;
    uint64_t offset = 0;
    uint64_t size = 0;
    size_t index = 0;
};

void set_message(const std::string& message) {
    std::lock_guard<std::mutex> lock(g_error_lock);
    g_error = message;
}

int fail(const std::string& message) {
    set_message(message);
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

void remove_tree(const std::string& path) {
    struct stat st{};

    if (lstat(path.c_str(), &st) != 0) {
        return;
    }

    if (!S_ISDIR(st.st_mode)) {
        unlink(path.c_str());
        return;
    }

    DIR* directory = opendir(path.c_str());

    if (!directory) {
        return;
    }

    while (dirent* entry = readdir(directory)) {
        const std::string name = entry->d_name;

        if (name == "." || name == "..") {
            continue;
        }

        remove_tree(path + "/" + name);
    }

    closedir(directory);
    rmdir(path.c_str());
}

bool make_directory(const std::string& path) {
    if (path.empty() || path[0] != '/') {
        return false;
    }

    for (size_t i = 1; i <= path.size(); ++i) {
        if (i != path.size() && path[i] != '/') {
            continue;
        }

        const std::string part = path.substr(0, i);

        if (part.empty()) {
            continue;
        }

        if (mkdir(part.c_str(), 0700) != 0 &&
            errno != EEXIST) {
            return false;
        }

        struct stat st{};

        if (stat(part.c_str(), &st) != 0 ||
            !S_ISDIR(st.st_mode)) {
            return false;
        }
    }

    return true;
}

bool load_shader_library(
    ShaderLibrary& library,
    std::string& error
) {
    library.handle = dlopen(
        "libp4gshader.so",
        RTLD_NOW | RTLD_LOCAL
    );

    if (!library.handle) {
        const char* detail = dlerror();

        error = "P4G: erro carregando libp4gshader.so: ";
        error += detail ? detail : "desconhecido";
        return false;
    }

    dlerror();

    library.version = reinterpret_cast<ShaderVersion>(
        dlsym(library.handle, "p4g_shader_api_version")
    );

    const char* version_error = dlerror();

    if (version_error || !library.version) {
        error = "P4G: p4g_shader_api_version ausente";
        return false;
    }

    const int version = library.version();

    if (version != SHADER_API_VERSION) {
        error = "P4G: API de shaders incompatível: ";
        error += std::to_string(version);
        error += " (esperada: 2)";
        return false;
    }

    dlerror();

    library.convert = reinterpret_cast<ShaderConvert>(
        dlsym(library.handle, "p4g_shader_convert")
    );

    const char* convert_error = dlerror();

    if (convert_error || !library.convert) {
        error = "P4G: p4g_shader_convert ausente";
        return false;
    }

    return true;
}

bool parse_unsigned(
    const std::string& text,
    uint64_t& value
) {
    if (text.empty()) {
        return false;
    }

    for (char c : text) {
        if (c < '0' || c > '9') {
            return false;
        }
    }

    try {
        size_t consumed = 0;

        value = std::stoull(text, &consumed, 10);

        return consumed == text.size();
    } catch (...) {
        return false;
    }
}

bool read_pack_index(
    const std::string& dir,
    const std::string& pack,
    std::vector<GxpEntry>& entries,
    std::string& error
) {
    std::ifstream file(dir + "/" + pack + ".tsv");

    if (!file) {
        error = "P4G: indice ausente: " + pack;
        return false;
    }

    std::string line;

    if (!std::getline(file, line) ||
        line != "nome\toffset\ttamanho") {
        error = "P4G: cabecalho TSV invalido: " + pack;
        return false;
    }

    size_t index = 0;

    while (std::getline(file, line)) {
        if (!line.empty() && line.back() == '\r') {
            line.pop_back();
        }

        if (line.empty()) {
            continue;
        }

        const size_t a = line.find('\t');

        const size_t b = a == std::string::npos
            ? std::string::npos
            : line.find('\t', a + 1);

        if (a == std::string::npos ||
            b == std::string::npos ||
            line.find('\t', b + 1) != std::string::npos) {
            error = "P4G: linha TSV invalida em " + pack;
            return false;
        }

        GxpEntry entry;

        entry.pack = pack;
        entry.name = line.substr(0, a);
        entry.index = index++;

        if (entry.name.empty() ||
            !parse_unsigned(
                line.substr(a + 1, b - a - 1),
                entry.offset
            ) ||
            !parse_unsigned(
                line.substr(b + 1),
                entry.size
            ) ||
            entry.size == 0 ||
            entry.size > MAX_GXP_SIZE) {
            error = "P4G: registro TSV invalido em " + pack;
            return false;
        }

        entries.push_back(std::move(entry));
    }

    if (!file.eof() && file.fail()) {
        error = "P4G: erro lendo indice " + pack;
        return false;
    }

    return true;
}

bool read_gxp(
    const std::string& dir,
    const GxpEntry& entry,
    std::vector<uint8_t>& bytes
) {
    std::ifstream file(
        dir + "/" + entry.pack,
        std::ios::binary | std::ios::ate
    );

    if (!file) {
        return false;
    }

    const std::streampos end = file.tellg();

    if (end < 0) {
        return false;
    }

    const uint64_t file_size =
        static_cast<uint64_t>(end);

    if (entry.offset > file_size ||
        entry.size > file_size - entry.offset ||
        entry.size >
            static_cast<uint64_t>(
                std::numeric_limits<std::streamsize>::max()
            ) ||
        entry.offset >
            static_cast<uint64_t>(
                std::numeric_limits<std::streamoff>::max()
            )) {
        return false;
    }

    bytes.resize(static_cast<size_t>(entry.size));

    file.seekg(
        static_cast<std::streamoff>(entry.offset),
        std::ios::beg
    );

    if (!file) {
        return false;
    }

    return static_cast<bool>(
        file.read(
            reinterpret_cast<char*>(bytes.data()),
            static_cast<std::streamsize>(bytes.size())
        )
    );
}

uint32_t read_u32(const uint8_t* p) {
    return uint32_t(p[0]) |
           (uint32_t(p[1]) << 8) |
           (uint32_t(p[2]) << 16) |
           (uint32_t(p[3]) << 24);
}

bool verify_spirv(const std::string& path) {
    struct stat st{};

    if (stat(path.c_str(), &st) != 0 ||
        !S_ISREG(st.st_mode) ||
        st.st_size < 24 ||
        static_cast<uint64_t>(st.st_size) >
            MAX_SPIRV_SIZE ||
        (st.st_size % 4) != 0) {
        return false;
    }

    std::ifstream file(path, std::ios::binary);

    if (!file) {
        return false;
    }

    std::vector<uint8_t> bytes(
        static_cast<size_t>(st.st_size)
    );

    if (!file.read(
            reinterpret_cast<char*>(bytes.data()),
            static_cast<std::streamsize>(bytes.size())
        )) {
        return false;
    }

    if (read_u32(bytes.data()) != 0x07230203u ||
        read_u32(bytes.data() + 12) == 0 ||
        read_u32(bytes.data() + 16) != 0) {
        return false;
    }

    const size_t words = bytes.size() / 4;

    size_t pos = 5;

    while (pos < words) {
        const uint32_t instruction =
            read_u32(bytes.data() + pos * 4);

        const size_t count = instruction >> 16;

        if (count == 0 || count > words - pos) {
            return false;
        }

        pos += count;
    }

    return pos == words;
}

bool write_report(
    const std::string& path,
    const std::string& contents
) {
    const std::string temporary = path + ".tmp";

    FILE* file = std::fopen(temporary.c_str(), "wb");

    if (!file) {
        return false;
    }

    const bool ok =
        std::fwrite(
            contents.data(),
            1,
            contents.size(),
            file
        ) == contents.size();

    const bool closed = std::fclose(file) == 0;

    if (!ok || !closed ||
        std::rename(
            temporary.c_str(),
            path.c_str()
        ) != 0) {
        std::remove(temporary.c_str());
        return false;
    }

    return true;
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
    (void)data_dir;
    (void)jobs;

    g_phase = 1;
    g_done = 0;
    g_total = 0;
    set_message("");

    if (nsp_fd < 0) {
        return fail("P4G: arquivo do jogo invalido");
    }

    std::vector<nxi::InputFile> inputs;

    inputs.push_back({nsp_name, nsp_fd});

    const int keys_fd = open(
        keys.c_str(),
        O_RDONLY | O_CLOEXEC
    );

    if (keys_fd < 0) {
        return fail("P4G: prod.keys nao encontrado");
    }

    inputs.push_back({"prod.keys", keys_fd});

    const int title_fd = title_keys.empty()
        ? -1
        : open(
            title_keys.c_str(),
            O_RDONLY | O_CLOEXEC
        );

    if (title_fd >= 0) {
        inputs.push_back({"title.keys", title_fd});
    }

    std::string error;

    auto game = nxi::GameImage::open(inputs, 0, error);

    close(keys_fd);

    if (title_fd >= 0) {
        close(title_fd);
    }

    if (!game) {
        return fail(
            error.empty()
                ? "P4G: falha ao abrir o jogo"
                : error
        );
    }

    if (!is_supported_title(game->title_id())) {
        return fail("P4G: Title ID nao corresponde ao jogo");
    }

    const auto main_it = game->exefs().find("main");

    if (main_it == game->exefs().end() ||
        main_it->second.size() < 0x48) {
        return fail("P4G: executavel main invalido");
    }

    if (work_dir.empty() ||
        work_dir[0] != '/' ||
        work_dir == "/" ||
        work_dir == "." ||
        work_dir == ".." ||
        work_dir.find("/../") != std::string::npos ||
        work_dir.find("/./") != std::string::npos ||
        work_dir.size() >= 3 &&
            work_dir.compare(
                work_dir.size() - 3,
                3,
                "/.."
            ) == 0) {
        return fail("P4G: diretorio de trabalho invalido");
    }

    // ETAPA 2 - Extrair os programas GXP.

    g_phase = 2;

    remove_tree(work_dir);

    if (!make_directory(work_dir)) {
        return fail("P4G: falha criando diretorio de trabalho");
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

    if (blocks <= 0) {
        return fail(
            error.empty()
                ? "P4G: nenhum GXP extraido"
                : error
        );
    }

    // ETAPA 3 - Carregar o recompilador.

    ShaderLibrary shader;

    if (!load_shader_library(shader, error)) {
        return fail(error);
    }

    const std::string source_dir =
        extracted + "/p4g_gxp";

    const std::string output_dir =
        work_dir + "/spirv";

    if (!make_directory(output_dir)) {
        return fail("P4G: falha criando pasta SPIR-V");
    }

    const char* packs[] = {
        "output_cnv_v_standard.bin",
        "output_cnv_f_standard.bin",
        "output_cnv_v_effect.bin",
        "output_cnv_f_effect.bin",
        "output_cnv_v_sprite.bin",
        "output_cnv_f_sprite.bin"
    };

    std::vector<GxpEntry> entries;

    for (const char* pack : packs) {
        if (!read_pack_index(
                source_dir,
                pack,
                entries,
                error
            )) {
            return fail(error);
        }
    }

    if (entries.size() != static_cast<size_t>(blocks) ||
        entries.size() >
            static_cast<size_t>(
                std::numeric_limits<int>::max()
            )) {
        return fail(
            "P4G: indices GXP nao correspondem "
            "a quantidade extraida"
        );
    }

    g_phase = 3;
    g_done = 0;
    g_total = static_cast<int>(entries.size());

    int success = 0;
    int failed = 0;

    std::string report =
        "pacote\tindice\tnome\tresultado\tcodigo\n";

    std::string current_pack;
    std::string pack_output;

    for (const GxpEntry& entry : entries) {
        if (current_pack != entry.pack) {
            current_pack = entry.pack;
            pack_output = output_dir + "/" + entry.pack;

            if (!make_directory(pack_output)) {
                return fail(
                    "P4G: falha criando pasta SPIR-V "
                    "para " + entry.pack
                );
            }
        }

        char filename[32];

        std::snprintf(
            filename,
            sizeof(filename),
            "%06zu.spv",
            entry.index
        );

        const std::string output_path =
            pack_output + "/" + filename;

        std::vector<uint8_t> gxp;

        int result = -100;

        if (read_gxp(source_dir, entry, gxp)) {
            result = shader.convert(
                gxp.data(),
                gxp.size(),
                output_path.c_str()
            );

            if (result == 0 &&
                !verify_spirv(output_path)) {
                result = -101;
            }
        }

        if (result == 0) {
            ++success;
        } else {
            ++failed;
            unlink(output_path.c_str());
        }

        report += entry.pack;
        report += "\t";
        report += std::to_string(entry.index);
        report += "\t";
        report += entry.name;
        report += "\t";
        report += result == 0 ? "PASS" : "FAIL";
        report += "\t";
        report += std::to_string(result);
        report += "\n";

        g_done.fetch_add(1);
    }

    // ETAPA 4 - Relatorios.

    g_phase = 4;

    const std::string report_path =
        output_dir + "/conversion-results.tsv";

    if (!write_report(report_path, report)) {
        return fail(
            "P4G: falha salvando relatorio de conversao"
        );
    }

    const std::string summary =
        "total\t" + std::to_string(entries.size()) +
        "\nsucesso\t" + std::to_string(success) +
        "\nfalhas\t" + std::to_string(failed) +
        "\n";

    if (!write_report(
            output_dir + "/summary.tsv",
            summary
        )) {
        return fail("P4G: falha salvando resumo SPIR-V");
    }

    if (failed > 0) {
        return fail(
            "P4G: conversao parcial. " +
            std::to_string(success) +
            " GXP convertidos; " +
            std::to_string(failed) +
            " falharam. Relatorio: " +
            report_path
        );
    }

    g_done = static_cast<int>(entries.size());
    g_total = static_cast<int>(entries.size());
    g_phase = 5;

    set_message(
        "P4G: " +
        std::to_string(success) +
        " programas GXP convertidos para SPIR-V. "
        "Validacao estrutural basica concluida. "
        "Execucao do jogo ainda nao implementada."
    );

    return 0;
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

    return env->NewStringUTF(g_error.c_str());
}

} // extern "C"
