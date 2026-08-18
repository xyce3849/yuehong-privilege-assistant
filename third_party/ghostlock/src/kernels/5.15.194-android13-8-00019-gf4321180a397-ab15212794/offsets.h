/* 5.15.194-android13-8-00019-gf4321180a397-ab15212794 */
/* Xiaomi 13 Pro (SM8550). 5.15 uses plist_node (no rb_tree) for the PI
 * chain; the write primitive is the mcast+plist path, not pselect. */

OFFSETS_ENTRY(
    "5.15.194-android13-8-00019-gf4321180a397-ab15212794",
    STRUCT_OFFSETS_5_15,
    .off_init_task = 0x02c53440,
    .off_init_cred = 0x02c0d5d8,
    .off_root_task_group = 0x02d68ac0,
    .off_selinux_enforcing = 0x02dbad88,
    .off_selinux_blob_sizes = 0x02169da8,
    .off_security_hook_heads = 0x02167920,
    .off_slide_nfulnl_logger = 0x02b11e30,
    .off_slide_loggers_0_1 = 0x02b11d68,
    .off_slide_boot_id = 0x02dd6819,
),