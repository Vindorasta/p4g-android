#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <atomic>
#include <chrono>
#include <filesystem>
#include <fstream>
#include <mutex>
#include <set>
#include <stdexcept>
#include <string>
#include "nxgame.h"
#include "p4g_maxwell.h"
#include "nvn_local_shader.h"
#include "nvn_shaders.h"

namespace {
std::atomic<int> phase{0}, done{0}, total{0};
std::mutex message_lock, run_lock;
std::string message;
void set_message(const std::string& text) { std::lock_guard guard(message_lock); message = text; }
int fail(const std::string& text) { set_message(text); phase = 0; return 1; }
std::string jstr(JNIEnv* env, jstring text) {
    if (!text) return {};
    const char* p = env->GetStringUTFChars(text, nullptr);
    if (!p) return {};
    std::string s(p); env->ReleaseStringUTFChars(text, p); return s;
}
bool write(const std::filesystem::path& path, const std::string& text) {
    std::ofstream f(path, std::ios::binary | std::ios::trunc);
    f << text; f.flush(); return bool(f);
}
int run(int fd, const std::string& name, const std::string& keys, const std::string& title_keys,
        const std::string& data_dir) {
    std::unique_lock guard(run_lock, std::try_to_lock);
    if (!guard.owns_lock()) return 1;
    phase = 1; done = 0; total = 0; set_message("");
    namespace fs = std::filesystem;
    fs::path staging;
    try {
        if (fd < 0) return fail("Invalid game file");
        fs::path root(data_dir);
        if (!root.is_absolute() || root == root.root_path() || root.lexically_normal() != root)
            return fail("Invalid data directory");
        std::vector<nxi::InputFile> inputs{{name, fd}};
        int k = open(keys.c_str(), O_RDONLY | O_CLOEXEC);
        if (k < 0) return fail("prod.keys not found");
        inputs.push_back({"prod.keys", k});
        int t = title_keys.empty() ? -1 : open(title_keys.c_str(), O_RDONLY | O_CLOEXEC);
        if (t >= 0) inputs.push_back({"title.keys", t});
        std::string error;
        auto game = nxi::GameImage::open(inputs, 0, error);
        close(k); if (t >= 0) close(t);
        if (!game) return fail(error.empty() ? "Could not open the game" : error);
        if ((game->title_id() & ~0xfffull) != 0x010062B01525C000ull) return fail("Title ID does not match P4G");
        if (!game->exefs().count("main")) return fail("The game has no main executable");
        if (!nx::nvn::local::available(error)) return fail(error);

        fs::create_directories(root);
        std::string suffix = std::to_string(std::chrono::system_clock::now().time_since_epoch().count());
        staging = root / ("spirv-preparing-" + suffix);
        if (!fs::create_directory(staging)) return fail("Could not create a fresh shader cache");
        // Probe is compiled on the device, then removed. It is never a bundled game shader.
        fs::path probe = staging / "compiler-test";
        if (!nx::nvn::local::probe(probe.string(), error)) throw std::runtime_error(error);
        fs::remove_all(probe);
        if (!write(staging / "results.tsv", "hash\tresources\n")) throw std::runtime_error("Cannot write resource table");
        std::set<std::string> hashes;
        phase = 2;
        total = int(game->exefs().size()); done = 0;
        for (const auto& [module, nso] : game->exefs()) {
            if (nso.size() < 4 || std::memcmp(nso.data(), "NSO0", 4)) { ++done; continue; }
            std::vector<uint8_t> flat;
            if (!nxi::nso_to_flat_image(nso, flat, error)) throw std::runtime_error("Invalid " + module + ": " + error);
            auto blocks = p4g::maxwell_blocks(flat);
            phase = 3;
            for (const auto& block : blocks) {
                std::string hash = nx::nvn::shader_hash(flat.data() + block.code, block.size);
                if (!hashes.insert(hash).second) continue;
                if (!nx::nvn::local::compile(staging.string(), flat.data() + block.code, block.size,
                        flat.data() + block.control, 0x880, hash, error))
                    throw std::runtime_error("Shader " + hash + ": " + error);
            }
            ++done; phase = 2;
        }
        phase = 4;
        std::string marker = std::string("format\t") + nx::nvn::local::kFormat + "\n";
        for (const auto& hash : hashes) {
            if (!nx::nvn::local::valid(staging.string(), hash)) throw std::runtime_error("Incomplete generated cache");
            marker += "seed\t" + hash + "\n";
        }
        if (!write(staging / "prepared.tsv", marker)) throw std::runtime_error("Cannot save preparation status");
        fs::path current = root / "spirv", backup = root / ("spirv-backup-" + suffix);
        bool had_cache = fs::exists(current);
        if (had_cache) fs::rename(current, backup);
        std::error_code commit_error;
        fs::rename(staging, current, commit_error);
        if (commit_error) {
            std::error_code restore_error;
            if (had_cache) fs::rename(backup, current, restore_error);
            throw std::runtime_error("Cannot install generated cache. Previous cache kept at " +
                                     (restore_error ? backup : current).string());
        }
        staging.clear();
        phase = 5;
        set_message("Preparation complete. Additional shaders will be generated on first use.");
        return 0;
    } catch (const std::exception& e) {
        // Only this invocation's private staging directory is removed; saves and old caches stay intact.
        if (!staging.empty()) { std::error_code ignored; fs::remove_all(staging, ignored); }
        return fail(e.what());
    }
}
}
extern "C" {
JNIEXPORT jint JNICALL Java_io_github_p4grecomp_Importer_run(JNIEnv* env, jclass, jint fd, jstring name,
        jstring keys, jstring title_keys, jstring data_dir, jstring, jint) {
    return run(fd, jstr(env, name), jstr(env, keys), jstr(env, title_keys), jstr(env, data_dir));
}
JNIEXPORT jintArray JNICALL Java_io_github_p4grecomp_Importer_progress(JNIEnv* env, jclass) {
    jint values[] = {phase.load(), done.load(), total.load()};
    auto out = env->NewIntArray(3); if (out) env->SetIntArrayRegion(out, 0, 3, values); return out;
}
JNIEXPORT jstring JNICALL Java_io_github_p4grecomp_Importer_error(JNIEnv* env, jclass) {
    std::lock_guard guard(message_lock); return env->NewStringUTF(message.c_str());
}
}
