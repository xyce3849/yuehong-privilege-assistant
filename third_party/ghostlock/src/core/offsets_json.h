#ifndef OFFSETS_JSON_H
#define OFFSETS_JSON_H

#include "offsets.h"

/*
 * Parse the runtime offsets file (offsets.json) and fill *out.
 * Returns 0 on success, non-zero on failure.
 *
 * The file format is a JSON array; the first object's "symbols" map is read
 * using g_symbol_map[] (field name -> offsetof(struct kernel_offsets, ...)).
 *
 * NOTE: fields consumed by W1.5 (off_modules_disabled,
 * off_oplus_harden_init_succeed, off_oplus_guard_cleanup) are stored as
 * ABSOLUTE kernel virtual addresses -- they must NOT be run through
 * data_addr() / KIMAGE_TEXT_BASE at read time.
 */
int load_offsets_json(const char *path, const char *release,
                      struct kernel_offsets *out, char *release_buf,
                      size_t release_buf_cap);

#endif
