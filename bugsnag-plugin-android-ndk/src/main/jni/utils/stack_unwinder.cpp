#include "stack_unwinder.h"

#include "string.h"

#include <dlfcn.h>
#include <unwindstack/LocalUnwinder.h>
#include <unwindstack/Maps.h>
#include <unwindstack/MemoryLocal.h>
#include <unwindstack/Regs.h>
#include <unwindstack/RegsGetLocal.h>
#include <unwindstack/Unwinder.h>

// unwinder intended for a potentially terminating context
static unwindstack::Unwinder *crash_time_unwinder;
// soft lock for using the crash time unwinder - if active, return without
// attempting to unwind. This isn't a "real" lock to avoid deadlocking in the
// event of a crash while handling an ANR or the reverse.
static bool unwinding_crash_stack;

// Thread-safe, reusable unwinder - uses thread-specific memory caches
static unwindstack::LocalUnwinder *current_time_unwinder;

static bool attempted_init;

static void bsg_fallback_symbols(const uint64_t addr,
                                 bugsnag_stackframe *dst_frame) {
  Dl_info info;
  if (dladdr((void *)addr, &info) != 0) {
    if (info.dli_fname != nullptr) {
      bsg_strncpy(dst_frame->filename, (char *)info.dli_fname,
                  sizeof(dst_frame->filename));
    }
    if (info.dli_sname != nullptr) {
      bsg_strncpy(dst_frame->method, (char *)info.dli_sname,
                  sizeof(dst_frame->method));
    }
  }
}

static bool bsg_check_invalid_libname(const std::string &filename) {
  return filename.empty() ||
         // if the filename ends-in ".apk" then the lib was loaded without
         // extracting it from the apk we consider these as "invalid" to trigger
         // the use of a fallback filename
         (filename.length() >= 4 &&
          filename.substr(filename.length() - 4, 4) == ".apk");
}

void bsg_unwinder_init() {
  if (attempted_init) {
    // already initialized or failed to init, cannot be done more than once
    return;
  }
  attempted_init = true;

  auto crash_time_maps = new unwindstack::LocalMaps();
  if (crash_time_maps->Parse()) {
    std::shared_ptr<unwindstack::Memory> crash_time_memory(
        new unwindstack::MemoryLocal);
    crash_time_unwinder = new unwindstack::Unwinder(
        BUGSNAG_FRAMES_MAX, crash_time_maps,
        unwindstack::Regs::CreateFromLocal(), crash_time_memory);
    auto arch = unwindstack::Regs::CurrentArch();
    auto dexfiles_ptr = unwindstack::CreateDexFiles(arch, crash_time_memory);
    crash_time_unwinder->SetDexFiles(dexfiles_ptr.get());
  }

  current_time_unwinder = new unwindstack::LocalUnwinder();
  if (!current_time_unwinder->Init()) {
    delete current_time_unwinder;
    current_time_unwinder = nullptr;
  }
}

ssize_t bsg_unwind_crash_stack(bugsnag_stackframe stack[BUGSNAG_FRAMES_MAX],
                               siginfo_t *info, void *user_context) {
  if (crash_time_unwinder == nullptr || unwinding_crash_stack) {
    return 0;
  }
  unwinding_crash_stack = true;
  if (user_context) {
    crash_time_unwinder->SetRegs(unwindstack::Regs::CreateFromUcontext(
        unwindstack::Regs::CurrentArch(), user_context));
  } else {
    auto regs = unwindstack::Regs::CreateFromLocal();
    unwindstack::RegsGetLocal(regs);
    crash_time_unwinder->SetRegs(regs);
  }

  crash_time_unwinder->Unwind();
  int frame_count = 0;
  for (auto &frame : crash_time_unwinder->frames()) {
    auto &dst_frame = stack[frame_count];
    dst_frame.frame_address = frame.pc;
    dst_frame.line_number = frame.rel_pc;
    dst_frame.load_address = frame.map_start;
    dst_frame.symbol_address = frame.pc - frame.function_offset;

    // if the filename or method name cannot be found (or are considered
    // invalid) - fallback to dladdr to find them
    if (bsg_check_invalid_libname(frame.map_name) ||
        frame.function_name.empty()) {
      bsg_fallback_symbols(frame.pc, &dst_frame);
    } else {
      bsg_strncpy(dst_frame.filename, frame.map_name.c_str(),
                  sizeof(dst_frame.filename));
      bsg_strncpy(dst_frame.method, frame.function_name.c_str(),
                  sizeof(dst_frame.method));
    }

    frame_count++;
  }
  unwinding_crash_stack = false;
  return frame_count;
}

ssize_t
bsg_unwind_concurrent_stack(bugsnag_stackframe stack[BUGSNAG_FRAMES_MAX],
                            siginfo_t *_info, void *_context) {
  if (current_time_unwinder == nullptr) {
    return 0;
  }

  std::vector<unwindstack::LocalFrameData> frames;
  current_time_unwinder->Unwind(&frames, BUGSNAG_FRAMES_MAX);
  int frame_count = 0;
  for (auto &frame : frames) {
    bugsnag_stackframe &dst_frame = stack[frame_count];
    dst_frame.frame_address = frame.pc;
    if (frame.map_info != nullptr) {
      dst_frame.line_number = frame.rel_pc;
      dst_frame.load_address = frame.map_info->start();
      dst_frame.symbol_address = frame.pc - frame.map_info->offset();

      // if the filename is empty or invalid, use the `Elf` info to get the
      // correct Soname if the function_name is empty as well, fallback to
      // dladdr for the symbol data
      if (bsg_check_invalid_libname(frame.map_info->name()) &&
          !frame.function_name.empty()) {

        bsg_strncpy(dst_frame.filename,
                    frame.map_info->elf()->GetSoname().c_str(),
                    sizeof(dst_frame.filename));
        bsg_strncpy(dst_frame.method, frame.function_name.c_str(),
                    sizeof(dst_frame.method));
      } else if (frame.function_name.empty()) {
        bsg_fallback_symbols(frame.pc, &dst_frame);
      } else {
        bsg_strncpy(dst_frame.filename, frame.map_info->name().c_str(),
                    sizeof(dst_frame.filename));
        bsg_strncpy(dst_frame.method, frame.function_name.c_str(),
                    sizeof(dst_frame.method));
      }
    }
    frame_count++;
  }
  return frame_count;
}
