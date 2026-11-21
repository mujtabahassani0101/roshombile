#include "utils/Json.hpp"

#include <algorithm>
#include <cctype>
#include <regex>
#include <sstream>

namespace omr::json {

namespace {

std::optional<size_t> keyPosition(const std::string& json, const std::string& key) {
  const std::regex keyPattern("\"" + key + "\"\\s*:");
  std::smatch match;
  if (!std::regex_search(json, match, keyPattern)) {
    return std::nullopt;
  }
  return static_cast<size_t>(match.position() + match.length());
}

std::optional<size_t> topLevelKeyPosition(const std::string& json, const std::string& key) {
  const std::regex keyPattern("\"" + key + "\"\\s*:");
  bool inString = false;
  bool escaped = false;
  int objectDepth = 0;
  int arrayDepth = 0;

  for (size_t index = 0; index < json.size(); ++index) {
    const char ch = json[index];
    if (inString) {
      if (escaped) {
        escaped = false;
      } else if (ch == '\\') {
        escaped = true;
      } else if (ch == '"') {
        inString = false;
      }
      continue;
    }

    if (ch == '"') {
      if (objectDepth == 1 && arrayDepth == 0) {
        const std::string tail = json.substr(index);
        std::smatch match;
        if (std::regex_search(tail, match, keyPattern) && match.position() == 0) {
          return index + static_cast<size_t>(match.length());
        }
      }
      inString = true;
    } else if (ch == '{') {
      ++objectDepth;
    } else if (ch == '}') {
      objectDepth = std::max(0, objectDepth - 1);
    } else if (ch == '[') {
      ++arrayDepth;
    } else if (ch == ']') {
      arrayDepth = std::max(0, arrayDepth - 1);
    }
  }

  return std::nullopt;
}

size_t skipWhitespace(const std::string& value, size_t index) {
  while (index < value.size() && std::isspace(static_cast<unsigned char>(value[index])) != 0) {
    ++index;
  }
  return index;
}

std::optional<std::string> balancedAfter(const std::string& json, size_t start, char open, char close) {
  start = skipWhitespace(json, start);
  if (start >= json.size() || json[start] != open) {
    return std::nullopt;
  }

  int depth = 0;
  bool inString = false;
  bool escaped = false;
  for (size_t index = start; index < json.size(); ++index) {
    const char ch = json[index];
    if (inString) {
      if (escaped) {
        escaped = false;
      } else if (ch == '\\') {
        escaped = true;
      } else if (ch == '"') {
        inString = false;
      }
      continue;
    }

    if (ch == '"') {
      inString = true;
    } else if (ch == open) {
      ++depth;
    } else if (ch == close) {
      --depth;
      if (depth == 0) {
        return json.substr(start, index - start + 1);
      }
    }
  }

  return std::nullopt;
}

std::optional<std::string> arrayForKey(const std::string& json, const std::string& key) {
  const auto pos = keyPosition(json, key);
  if (!pos.has_value()) {
    return std::nullopt;
  }
  return balancedAfter(json, *pos, '[', ']');
}

std::optional<std::string> stringAt(const std::string& json, std::optional<size_t> pos) {
  if (!pos.has_value()) {
    return std::nullopt;
  }

  size_t index = skipWhitespace(json, *pos);
  if (index >= json.size() || json[index] != '"') {
    return std::nullopt;
  }
  ++index;

  std::ostringstream out;
  bool escaped = false;
  for (; index < json.size(); ++index) {
    const char ch = json[index];
    if (escaped) {
      out << ch;
      escaped = false;
    } else if (ch == '\\') {
      escaped = true;
    } else if (ch == '"') {
      return out.str();
    } else {
      out << ch;
    }
  }

  return std::nullopt;
}

} // namespace

std::optional<std::string> objectForKey(const std::string& json, const std::string& key) {
  const auto pos = keyPosition(json, key);
  if (!pos.has_value()) {
    return std::nullopt;
  }
  return balancedAfter(json, *pos, '{', '}');
}

std::optional<std::string> topLevelObjectForKey(const std::string& json, const std::string& key) {
  const auto pos = topLevelKeyPosition(json, key);
  if (!pos.has_value()) {
    return std::nullopt;
  }
  return balancedAfter(json, *pos, '{', '}');
}

std::optional<std::string> stringForKey(const std::string& json, const std::string& key) {
  return stringAt(json, keyPosition(json, key));
}

std::optional<std::string> topLevelStringForKey(const std::string& json, const std::string& key) {
  return stringAt(json, topLevelKeyPosition(json, key));
}

std::optional<double> numberForKey(const std::string& json, const std::string& key) {
  const auto pos = keyPosition(json, key);
  if (!pos.has_value()) {
    return std::nullopt;
  }

  const std::string tail = json.substr(*pos);
  const std::regex pattern("^\\s*(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)");
  std::smatch match;
  if (!std::regex_search(tail, match, pattern)) {
    return std::nullopt;
  }
  return std::stod(match[1].str());
}

std::optional<int> intForKey(const std::string& json, const std::string& key) {
  auto value = numberForKey(json, key);
  if (!value.has_value()) {
    return std::nullopt;
  }
  return static_cast<int>(*value);
}

std::optional<bool> boolForKey(const std::string& json, const std::string& key) {
  const auto pos = keyPosition(json, key);
  if (!pos.has_value()) {
    return std::nullopt;
  }

  const std::string tail = json.substr(*pos);
  const std::regex pattern("^\\s*(true|false)");
  std::smatch match;
  if (!std::regex_search(tail, match, pattern)) {
    return std::nullopt;
  }
  return match[1].str() == "true";
}

std::vector<double> numberArrayForKey(const std::string& json, const std::string& key) {
  std::vector<double> values;
  const auto array = arrayForKey(json, key);
  if (!array.has_value()) {
    return values;
  }

  const std::regex numberPattern("-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?");
  auto begin = std::sregex_iterator(array->begin(), array->end(), numberPattern);
  auto end = std::sregex_iterator();
  for (auto it = begin; it != end; ++it) {
    values.push_back(std::stod((*it)[0].str()));
  }
  return values;
}

std::vector<std::string> stringArrayForKey(const std::string& json, const std::string& key) {
  std::vector<std::string> values;
  const auto array = arrayForKey(json, key);
  if (!array.has_value()) {
    return values;
  }

  // JSONObject on Android emits escaped forward slashes in filesystem paths.
  // Decode array elements the same way as scalar strings instead of returning
  // their raw JSON spelling. Keep escaped quotes/backslashes in one element.
  const std::regex stringPattern(R"json("((?:[^"\\]|\\.)*)")json");
  auto begin = std::sregex_iterator(array->begin(), array->end(), stringPattern);
  auto end = std::sregex_iterator();
  for (auto it = begin; it != end; ++it) {
    if (const auto value = stringAt(*array, static_cast<size_t>(it->position())); value.has_value()) {
      values.push_back(*value);
    }
  }
  return values;
}

std::vector<std::string> objectArrayForKey(const std::string& json, const std::string& key) {
  std::vector<std::string> values;
  const auto array = arrayForKey(json, key);
  if (!array.has_value()) {
    return values;
  }

  for (size_t index = 0; index < array->size(); ++index) {
    if ((*array)[index] != '{') {
      continue;
    }
    const auto object = balancedAfter(*array, index, '{', '}');
    if (object.has_value()) {
      values.push_back(*object);
      index += object->size();
    }
  }
  return values;
}

std::optional<Rect> rectForObject(const std::string& json) {
  const auto x = numberForKey(json, "x");
  const auto y = numberForKey(json, "y");
  const auto width = numberForKey(json, "width");
  const auto height = numberForKey(json, "height");
  if (!x.has_value() || !y.has_value() || !width.has_value() || !height.has_value()) {
    return std::nullopt;
  }
  return Rect{
    static_cast<float>(*x),
    static_cast<float>(*y),
    static_cast<float>(*width),
    static_cast<float>(*height),
  };
}

std::string stripFileUri(const std::string& uriOrPath) {
  constexpr const char* prefix = "file://";
  if (uriOrPath.rfind(prefix, 0) == 0) {
    return uriOrPath.substr(7);
  }
  return uriOrPath;
}

} // namespace omr::json
