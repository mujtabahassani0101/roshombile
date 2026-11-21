#include "utils/Json.hpp"
#include <iostream>
#include <stdexcept>

int main() {
  int checks = 0;
  auto expect = [&](const std::string& input, const std::string& key, const std::vector<std::string>& expected) {
    if (omr::json::stringArrayForKey(input, key) != expected)
      throw std::runtime_error("Native JSON string-array decoding regression");
    ++checks;
  };
  // Android JSONObject escapes forward slashes. Dart's encoder did not.
  expect(R"json({"templateJsonPaths":["\/data\/user\/0\/app\/first.json","\/private\/second.json"]})json",
         "templateJsonPaths", {"/data/user/0/app/first.json", "/private/second.json"});
  expect(R"json({"templateJsonPaths":["/data/first.json","/data/second.json"]})json",
         "templateJsonPaths", {"/data/first.json", "/data/second.json"});
  expect(R"json({"values":["a\"b","c\\d",""]})json", "values", {"a\"b", "c\\d", ""});
  expect(R"json({"values":["/private/برگه.json"]})json", "values", {"/private/برگه.json"});
  if (omr::json::topLevelIntForKey(R"({"qrBindings":[{"activeQuestionCount":75}],"activeQuestionCount":100})", "activeQuestionCount") != 100 ||
      omr::json::topLevelIntForKey(R"({"qrBindings":[{"activeQuestionCount":75}]})", "activeQuestionCount").has_value())
    throw std::runtime_error("Nested roster counts must not select the root page count");
  ++checks;
  std::cout << checks << " native JSON regression checks passed.\n";
}
