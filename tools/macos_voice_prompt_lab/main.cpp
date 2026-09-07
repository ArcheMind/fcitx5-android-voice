#include <llm/llm.hpp>

#include <rapidjson/document.h>

#include <chrono>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <memory>
#include <regex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

namespace fs = std::filesystem;
using MNN::Transformer::ChatMessages;
using MNN::Transformer::Llm;
using MNN::Transformer::LlmContext;
using MNN::Transformer::LlmStatus;

namespace {
struct Prompt {
    std::string id;
    std::string systemPromptTemplate;
    std::string contextTemplate;
    std::string audioTemplate;
};

struct Segment {
    fs::path audio;
    std::string expected;
};

struct TestCase {
    std::string id;
    std::string language;
    std::string context;
    std::vector<std::string> hotwords;
    std::vector<Segment> segments;
};

struct Experiment {
    fs::path modelDir;
    size_t repetitions = 1;
    std::vector<Prompt> prompts;
    std::vector<TestCase> cases;
};

std::string readFile(const fs::path& path) {
    std::ifstream input(path);
    if (!input) throw std::runtime_error("Cannot read " + path.string());
    return {std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>()};
}

std::string requiredString(const rapidjson::Value& object, const char* name) {
    if (!object.HasMember(name) || !object[name].IsString()) {
        throw std::runtime_error(std::string("Missing string field: ") + name);
    }
    return object[name].GetString();
}

std::string optionalString(const rapidjson::Value& object, const char* name) {
    return object.HasMember(name) && object[name].IsString() ? object[name].GetString() : "";
}

std::vector<std::string> strings(const rapidjson::Value& object, const char* name) {
    std::vector<std::string> result;
    if (!object.HasMember(name)) return result;
    if (!object[name].IsArray()) throw std::runtime_error(std::string("Expected array: ") + name);
    for (const auto& value : object[name].GetArray()) {
        if (!value.IsString()) throw std::runtime_error(std::string("Expected string in: ") + name);
        result.emplace_back(value.GetString());
    }
    return result;
}

size_t optionalPositiveSize(const rapidjson::Value& object, const char* name, size_t fallback) {
    if (!object.HasMember(name)) return fallback;
    const auto& value = object[name];
    if (!value.IsUint64() || value.GetUint64() == 0) {
        throw std::runtime_error(std::string("Expected positive integer: ") + name);
    }
    return static_cast<size_t>(value.GetUint64());
}

fs::path resolve(const fs::path& root, const std::string& value) {
    const fs::path path(value);
    return path.is_absolute() ? path : root / path;
}

Experiment parseExperiment(const fs::path& path, const fs::path& modelOverride) {
    rapidjson::Document document;
    document.Parse(readFile(path).c_str());
    if (document.HasParseError() || !document.IsObject()) {
        throw std::runtime_error("Experiment must be a valid JSON object");
    }
    const auto root = path.parent_path();
    Experiment experiment;
    experiment.modelDir = modelOverride.empty() ? resolve(root, requiredString(document, "model_dir")) : modelOverride;
    experiment.repetitions = optionalPositiveSize(document, "repetitions", 1);
    if (!document.HasMember("prompts") || !document["prompts"].IsArray()) {
        throw std::runtime_error("Experiment requires prompts array");
    }
    for (const auto& value : document["prompts"].GetArray()) {
        Prompt prompt{
            requiredString(value, "id"),
            requiredString(value, "system_prompt_template"),
            requiredString(value, "context_template"),
            requiredString(value, "audio_template"),
        };
        experiment.prompts.push_back(std::move(prompt));
    }
    if (!document.HasMember("cases") || !document["cases"].IsArray()) {
        throw std::runtime_error("Experiment requires cases array");
    }
    for (const auto& value : document["cases"].GetArray()) {
        TestCase testCase{requiredString(value, "id"), optionalString(value, "language"),
                          optionalString(value, "context"), strings(value, "hotwords"), {}};
        if (!value.HasMember("segments") || !value["segments"].IsArray()) {
            throw std::runtime_error("Each case requires segments array");
        }
        for (const auto& segment : value["segments"].GetArray()) {
            testCase.segments.push_back({resolve(root, requiredString(segment, "audio")),
                                         optionalString(segment, "expected")});
        }
        experiment.cases.push_back(std::move(testCase));
    }
    return experiment;
}

std::string replaceAll(std::string value, const std::string& needle, const std::string& replacement) {
    size_t position = 0;
    while ((position = value.find(needle, position)) != std::string::npos) {
        value.replace(position, needle.size(), replacement);
        position += replacement.size();
    }
    return value;
}

std::string join(const std::vector<std::string>& values) {
    std::ostringstream output;
    for (size_t i = 0; i < values.size(); ++i) {
        if (i) output << ", ";
        output << values[i];
    }
    return output.str();
}

std::string render(const std::string& pattern, const TestCase& testCase, const fs::path& audio = {}) {
    auto result = replaceAll(pattern, "{context}", testCase.context);
    result = replaceAll(result, "{hotwords}", join(testCase.hotwords));
    result = replaceAll(result, "{language}", testCase.language);
    return replaceAll(result, "{audio}", audio.string());
}

std::string trim(std::string text) {
    const auto begin = text.find_first_not_of(" \t\r\n");
    if (begin == std::string::npos) return "";
    const auto end = text.find_last_not_of(" \t\r\n");
    return text.substr(begin, end - begin + 1);
}

std::string normalize(std::string text) {
    static const std::regex terminalTokens("<eop>|<\\|im_end\\|>|<\\|endoftext\\|>", std::regex::icase);
    static const std::regex thinking("<think>[\\s\\S]*?</think>", std::regex::icase);
    static const std::regex chineseQuotedWrapper(
        R"(^(?:上传的)?音频(?:内容)?(?:是|为|中说的是)[：:]?\s*[“"]([\s\S]*)[”"](?:[。.]?\s*(?:如果|如有).*)?$)",
        std::regex::icase);
    static const std::regex englishQuotedWrapper(
        R"(^(?:the )?(?:uploaded )?audio(?: content)?(?: says| is| transcription is)?[：:]?\s*[“"]([\s\S]*)[”"](?:[。.]?\s*(?:if|let me know).*)?$)",
        std::regex::icase);
    static const std::regex resultPrefix(R"(^(?:转写|识别)(?:结果|文本)?[：:]\s*)", std::regex::icase);
    text = std::regex_replace(std::move(text), thinking, "");
    text = std::regex_replace(std::move(text), terminalTokens, "");
    text = trim(std::move(text));
    std::smatch match;
    if (std::regex_match(text, match, chineseQuotedWrapper) || std::regex_match(text, match, englishQuotedWrapper)) {
        return trim(match[1].str());
    }
    text = std::regex_replace(std::move(text), resultPrefix, "");
    return text;
}

std::string json(const std::string& value) {
    std::ostringstream output;
    output << '"';
    for (const auto character : value) {
        switch (character) {
            case '\\': output << "\\\\"; break;
            case '"': output << "\\\""; break;
            case '\n': output << "\\n"; break;
            case '\r': output << "\\r"; break;
            case '\t': output << "\\t"; break;
            default: output << character;
        }
    }
    return output.str() + '"';
}

void writeResult(std::ostream& output, const Prompt& prompt, const TestCase& testCase, const Segment& segment,
                 const std::string& systemPrompt, const std::string& contextMessage, const std::string& audioMessage,
                 size_t repetition, const std::string& raw, const std::string& error,
                 const LlmContext* context, long long wallUs) {
    output << "{\"prompt_id\":" << json(prompt.id)
           << ",\"case_id\":" << json(testCase.id)
           << ",\"repetition\":" << repetition
           << ",\"audio\":" << json(segment.audio.string())
           << ",\"expected\":" << json(segment.expected)
           << ",\"system_prompt\":" << json(systemPrompt)
           << ",\"context_message\":" << json(contextMessage)
           << ",\"audio_message\":" << json(audioMessage)
           << ",\"raw\":" << json(raw)
           << ",\"normalized\":" << json(normalize(raw))
           << ",\"error\":" << json(error)
           << ",\"wall_us\":" << wallUs;
    if (context) {
        output << ",\"audio_us\":" << context->audio_us
               << ",\"prefill_us\":" << context->prefill_us
               << ",\"decode_us\":" << context->decode_us
               << ",\"ttfa_us\":" << context->ttfa_us
               << ",\"prompt_len\":" << context->prompt_len
               << ",\"generated_tokens\":" << context->gen_seq_len;
    }
    output << "}\n";
}

void runPrompt(const Experiment& experiment, const Prompt& prompt, std::ostream& results) {
    if (experiment.cases.empty()) return;
    const auto systemPrompt = render(prompt.systemPromptTemplate, experiment.cases.front());
    std::unique_ptr<Llm> engine(Llm::createLLM((experiment.modelDir / "config.json").string()));
    if (!engine) throw std::runtime_error("MNN could not create the model");
    engine->set_config("{\"async\":false,\"has_talker\":false,\"is_visual\":false,\"max_new_tokens\":256,\"reuse_kv\":true,\"use_mmap\":true,\"system_prompt\":" + json(systemPrompt) + "}");
    if (!engine->load() || !engine->prefillFixedPrompt()) {
        throw std::runtime_error("MNN model load failed");
    }
    for (const auto& testCase : experiment.cases) {
        const auto caseSystemPrompt = render(prompt.systemPromptTemplate, testCase);
        if (caseSystemPrompt != systemPrompt) {
            throw std::runtime_error("A prompt variant may not vary its system prompt between cases; split it into prompt variants");
        }
        const auto contextMessage = render(prompt.contextTemplate, testCase);
        for (size_t repetition = 1; repetition <= experiment.repetitions; ++repetition) {
            if (!engine->beginChatSession()) throw std::runtime_error("MNN could not begin chat session");
            ChatMessages messages{{"system", systemPrompt}, {"user", contextMessage}};
            for (const auto& segment : testCase.segments) {
                const auto started = std::chrono::steady_clock::now();
                std::ostringstream raw;
                std::string error;
                const auto audioMessage = render(prompt.audioTemplate, testCase, segment.audio);
                messages.emplace_back("user", audioMessage);
                std::cerr << "MNN request: prompt_id=" << prompt.id << " case_id=" << testCase.id
                          << " repetition=" << repetition << " system=" << systemPrompt
                          << " context=" << contextMessage << " audio=" << audioMessage << '\n';
                engine->responseChatSession(messages, &raw, "<eop>", 256);
                const auto elapsed = std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - started).count();
                const auto* context = engine->getContext();
                if (context && context->status == LlmStatus::INTERNAL_ERROR) error = "MNN internal error";
                const auto normalized = normalize(raw.str());
                if (error.empty()) messages.emplace_back("assistant", normalized);
                std::cerr << "MNN response: prompt_id=" << prompt.id << " case_id=" << testCase.id
                          << " repetition=" << repetition << " raw=" << raw.str()
                          << " error=" << error << '\n';
                writeResult(results, prompt, testCase, segment, systemPrompt, contextMessage, audioMessage,
                            repetition, raw.str(), error, context, elapsed);
            }
            engine->endChatSession();
        }
    }
}
}

int main(int argc, char** argv) {
    if (argc < 2 || argc > 4) {
        std::cerr << "Usage: macos_voice_prompt_lab EXPERIMENT.json [RESULTS.jsonl] [MODEL_DIR]\n";
        return 2;
    }
    try {
        const fs::path experimentPath = fs::absolute(argv[1]);
        const fs::path resultPath = argc >= 3 ? fs::absolute(argv[2]) : fs::path();
        const fs::path modelOverride = argc >= 4 ? fs::absolute(argv[3]) : fs::path();
        const auto experiment = parseExperiment(experimentPath, modelOverride);
        std::ofstream file;
        std::ostream* results = &std::cout;
        if (!resultPath.empty()) {
            file.open(resultPath);
            if (!file) throw std::runtime_error("Cannot write " + resultPath.string());
            results = &file;
        }
        for (const auto& prompt : experiment.prompts) runPrompt(experiment, prompt, *results);
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "macos_voice_prompt_lab error: " << error.what() << '\n';
        return 1;
    }
}
