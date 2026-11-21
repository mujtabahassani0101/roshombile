#pragma once

#include <array>
#include <condition_variable>
#include <deque>
#include <functional>
#include <mutex>
#include <stdexcept>
#include <thread>

namespace omr {

// One process-wide header worker and one worker for each physical sheet family.
// Locks protect only queue operations; image processing never holds a lock.
// All C ABI calls share these queues, including concurrent single-page calls.
class EngineTaskQueues {
  class Queue {
  public:
    Queue() : worker_([this] { run(); }) {}
    ~Queue() { closeAndJoin(); }
    Queue(const Queue&) = delete;
    Queue& operator=(const Queue&) = delete;

    void push(std::function<void()> task) {
      {
        std::lock_guard lock(mutex_);
        if (closed_) throw std::runtime_error("engine_queue_closed");
        tasks_.push_back(std::move(task));
      }
      ready_.notify_one();
    }

    void closeAndJoin() {
      {
        std::lock_guard lock(mutex_);
        closed_ = true;
      }
      ready_.notify_all();
      if (worker_.joinable()) worker_.join();
    }

  private:
    void run() {
      for (;;) {
        std::function<void()> task;
        {
          std::unique_lock lock(mutex_);
          ready_.wait(lock, [this] { return closed_ || !tasks_.empty(); });
          if (tasks_.empty()) return;
          task = std::move(tasks_.front());
          tasks_.pop_front();
        }
        task();
      }
    }

    std::mutex mutex_;
    std::condition_variable ready_;
    std::deque<std::function<void()>> tasks_;
    bool closed_ = false;
    std::thread worker_;
  };

public:
  static EngineTaskQueues& instance() {
    static EngineTaskQueues queues;
    return queues;
  }

  void header(std::function<void()> task) { header_.push(std::move(task)); }
  void answers(size_t family, std::function<void()> task) {
    answers_.at(family).push(std::move(task));
  }

private:
  EngineTaskQueues() = default;
  ~EngineTaskQueues() {
    // Drain header handoffs before closing the answer queues they feed.
    header_.closeAndJoin();
    for (auto& queue : answers_) queue.closeAndJoin();
  }
  Queue header_;
  std::array<Queue, 3> answers_;
};

} // namespace omr
