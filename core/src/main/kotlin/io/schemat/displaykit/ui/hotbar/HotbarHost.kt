package io.schemat.displaykit.ui.hotbar

/**
 * Navigation surface shared by hotbar-style menus: slots receive the host in
 * their callbacks so they can push submenus, mutate the current level, or
 * close the menu — without caring whether the host is the floating
 * [VirtualHotbar] strip or the real-inventory
 * `io.schemat.displaykit.fabric.hotbar.HotbarMenu`.
 */
interface HotbarHost {
    fun push(slots: List<HotbarSlot>)
    fun pop()

    /** Replace the current level's slots in place (e.g. live relabel). */
    fun replaceCurrent(slots: List<HotbarSlot>)

    /** Tear the stack back to the root level. */
    fun popToRoot()

    /** Close the whole menu (restoring whatever it displaced). */
    fun close()
}
