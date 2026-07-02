package io.schemat.displaykit.render

data class BlockStateRef(val id: String) {
    companion object {
        val STONE = BlockStateRef("minecraft:stone")
        val SMOOTH_STONE = BlockStateRef("minecraft:smooth_stone")
        val WHITE_CONCRETE = BlockStateRef("minecraft:white_concrete")
        val BLACK_CONCRETE = BlockStateRef("minecraft:black_concrete")
        val GRAY_CONCRETE = BlockStateRef("minecraft:gray_concrete")
        val LIGHT_GRAY_CONCRETE = BlockStateRef("minecraft:light_gray_concrete")
        val RED_CONCRETE = BlockStateRef("minecraft:red_concrete")
        val GREEN_CONCRETE = BlockStateRef("minecraft:green_concrete")
        val BLUE_CONCRETE = BlockStateRef("minecraft:blue_concrete")
        val YELLOW_CONCRETE = BlockStateRef("minecraft:yellow_concrete")
        val ORANGE_CONCRETE = BlockStateRef("minecraft:orange_concrete")
        val LIME_CONCRETE = BlockStateRef("minecraft:lime_concrete")
        val CYAN_CONCRETE = BlockStateRef("minecraft:cyan_concrete")
        val LIGHT_BLUE_CONCRETE = BlockStateRef("minecraft:light_blue_concrete")
        val PURPLE_CONCRETE = BlockStateRef("minecraft:purple_concrete")
        val MAGENTA_CONCRETE = BlockStateRef("minecraft:magenta_concrete")
        val PINK_CONCRETE = BlockStateRef("minecraft:pink_concrete")
        val IRON_BLOCK = BlockStateRef("minecraft:iron_block")
        val GOLD_BLOCK = BlockStateRef("minecraft:gold_block")
        val DIAMOND_BLOCK = BlockStateRef("minecraft:diamond_block")
        val GLASS = BlockStateRef("minecraft:glass")
        val AIR = BlockStateRef("minecraft:air")
    }
}
