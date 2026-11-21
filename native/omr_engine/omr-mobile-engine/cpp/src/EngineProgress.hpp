#pragma once

#include <algorithm>
#include <map>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

namespace omr::detail {

// Telemetry only: no image paths, QR contents, identity or answer observations.
// Recognition never waits for the UI and its four FIFOs remain independent.
struct ProgressPage {
  int page;
  std::string type;
  std::string state = "header_queued";
};

class BatchProgress {
  mutable std::mutex mutex_;
  std::vector<ProgressPage> pages_;
public:
  explicit BatchProgress(const std::vector<int>& pages) {
    for (int page : pages) pages_.push_back({page, "", "header_queued"});
  }
  void update(size_t index, const std::string& state, const std::string& type = "") {
    std::lock_guard<std::mutex> lock(mutex_);
    if (index >= pages_.size()) return;
    auto& page = pages_[index];
    page.state = state;
    if (type == "T1" || type == "T2" || type == "T3") page.type = type;
  }
  bool complete() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return std::all_of(pages_.begin(), pages_.end(), [](const auto& page) {
      return page.state == "done" || page.state == "failed";
    });
  }
  std::string json() const {
    std::lock_guard<std::mutex> lock(mutex_);
    std::ostringstream out;
    out << "{\"pages\":[";
    for (size_t i = 0; i < pages_.size(); ++i) {
      const auto& p = pages_[i];
      if (i) out << ',';
      out << "{\"page\":" << p.page << ",\"type\":";
      if (p.type.empty()) out << "null"; else out << '"' << p.type << '"';
      out << ",\"state\":\"" << p.state << "\"}";
    }
    out << "]}";
    return out.str();
  }
};

class ProgressRegistry {
  std::mutex mutex_;
  std::map<std::string, std::shared_ptr<BatchProgress>> batches_;
public:
  std::shared_ptr<BatchProgress> create(const std::string& id, const std::vector<int>& pages) {
    if (id.empty() || id.size() > 64 || !std::all_of(id.begin(), id.end(), [](char c) {
      return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
        (c >= '0' && c <= '9') || c == '-';
    })) return {};
    std::lock_guard<std::mutex> lock(mutex_);
    // Bound abandoned telemetry from non-Flutter clients. Never evict active
    // work or let a duplicate ID mix two concurrent batches.
    if (batches_.contains(id)) return {};
    if (batches_.size() >= 64) {
      const auto done = std::find_if(batches_.begin(), batches_.end(), [](const auto& entry) {
        return entry.second->complete();
      });
      if (done == batches_.end()) return {};
      batches_.erase(done);
    }
    auto result = std::make_shared<BatchProgress>(pages);
    batches_.emplace(id, result);
    return result;
  }
  std::string json(const std::string& id) {
    std::shared_ptr<BatchProgress> batch;
    {
      std::lock_guard<std::mutex> lock(mutex_);
      const auto entry = batches_.find(id);
      if (entry == batches_.end()) return "{}";
      batch = entry->second;
    }
    return batch->json();
  }
  void release(const std::string& id) {
    std::lock_guard<std::mutex> lock(mutex_);
    batches_.erase(id);
  }
};

} // namespace omr::detail
