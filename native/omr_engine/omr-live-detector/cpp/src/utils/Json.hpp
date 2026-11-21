#pragma once

#include <optional>
#include <string>
#include <vector>
#include "omr/Geometry.hpp"

namespace omr::json {

std::optional<std::string> objectForKey(const std::string& json, const std::string& key);
std::optional<std::string> topLevelObjectForKey(const std::string& json, const std::string& key);
std::optional<std::string> stringForKey(const std::string& json, const std::string& key);
std::optional<std::string> topLevelStringForKey(const std::string& json, const std::string& key);
std::optional<double> numberForKey(const std::string& json, const std::string& key);
std::optional<int> intForKey(const std::string& json, const std::string& key);
std::optional<bool> boolForKey(const std::string& json, const std::string& key);
std::vector<double> numberArrayForKey(const std::string& json, const std::string& key);
std::vector<std::string> stringArrayForKey(const std::string& json, const std::string& key);
std::vector<std::string> objectArrayForKey(const std::string& json, const std::string& key);
std::optional<Rect> rectForObject(const std::string& json);
std::string stripFileUri(const std::string& uriOrPath);

} // namespace omr::json
