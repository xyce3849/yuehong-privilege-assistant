#ifndef OFFSETS_JSON_H
#define OFFSETS_JSON_H

#include <stddef.h>
#include "../kernels/offsets.h"

/* Load a kernel table from a JSON file (single object or array of objects)
 * and fill `out` for the kernel whose "release" equals `release`.  The format
 * matches tools/extract_rs/ghostlock-extract --format json, plus the
 * pselect_waiter_shift field.  The OTA-only caller zeroes `out` before loading,
 * so fields can only originate from the parsed JSON.  Returns 0 and sets *out on match; -1 when
 * no entry matches or the file is unreadable/malformed.  release_buf
 * receives a copy of the matched release string that stays valid after the
 * call. */
int load_offsets_json(const char *path, const char *release,
                      struct kernel_offsets *out, char *release_buf,
                      size_t release_buf_cap);

#endif
