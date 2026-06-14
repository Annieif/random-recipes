import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * 生成 Random Recipes Mod 的像素图标 (64x64 PNG)
 *
 * 设计思路：
 * - 主体：Minecraft 风格的工作台 (crafting table)
 * - 中心：3x3 原料格，每格放不同颜色的方块/物品
 * - 颜色故意打破 MC 原版的规律，象征"配方被打乱"
 * - 用暗棕/橙棕木纹色做背景，明亮颜色做物品
 */
public class GenerateIcon {

    // ===== 调色板 =====
    private static final int C_TRANS = 0x00_000000; // 透明

    // 木头 - 工作台
    private static final int C_WOOD_DARK = 0xFF_4A2F1A;
    private static final int C_WOOD = 0xFF_7A4B20;
    private static final int C_WOOD_LIGHT = 0xFF_9A6B3A;
    private static final int C_WOOD_HILIGHT = 0xFF_B98852;

    // 工作台网格线
    private static final int C_GRID = 0xFF_2A1A0F;
    private static final int C_SLOT_SHADOW = 0xFF_3A2515;
    private static final int C_SLOT_HILIGHT = 0xFF_D9AE7A;

    // 物品颜色 (每个物品是一个 8x8 的"方块")
    private static final int[][] ITEMS = {
            // 左上 - 红 (红石)
            { 0xFF_4A0000, 0xFF_8A2B2B, 0xFF_BF3A3A, 0xFF_FF5252 },
            // 中上 - 青 (钻石)
            { 0xFF_1A5A5A, 0xFF_2D9D9D, 0xFF_5FD7D7, 0xFF_9FEEEE },
            // 右上 - 黄 (金)
            { 0xFF_5A4A00, 0xFF_9A8210, 0xFF_D4B22A, 0xFF_FFE55C },
            // 左中 - 紫 (紫颂果)
            { 0xFF_3A1240, 0xFF_6A3277, 0xFF_9A5AB0, 0xFF_CA90DD },
            // 正中 - 白 (骨粉)
            { 0xFF_5A5A5A, 0xFF_9A9A9A, 0xFF_D8D8D8, 0xFF_FFFFFF },
            // 右中 - 橙 (铜)
            { 0xFF_4A2A00, 0xFF_8A5A2A, 0xFF_C08835, 0xFF_F0B878 },
            // 左下 - 绿 (绿宝石)
            { 0xFF_0A3A1A, 0xFF_1A7A3A, 0xFF_3ACC5A, 0xFF_7AFF88 },
            // 中下 - 蓝 (青金石)
            { 0xFF_0A1A5A, 0xFF_1A3A9A, 0xFF_3A6ADC, 0xFF_7AADFF },
            // 右下 - 深灰 (末影珍珠/下界之星)
            { 0xFF_1A1A2A, 0xFF_3A3A5A, 0xFF_7A7A9A, 0xFF_B8B8D8 }
    };

    public static void main(String[] args) throws IOException {
        int W = 64;
        int H = 64;
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);

        // ===== 1. 画边框阴影 (圆角效果) =====
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                // 四角透明，做圆角
                int cornerSize = 4;
                boolean inCorner =
                        (x < cornerSize && y < cornerSize && ((cornerSize - 1 - x) + (cornerSize - 1 - y) >= cornerSize)) ||
                        (x >= W - cornerSize && y < cornerSize && ((x - (W - cornerSize)) + (cornerSize - 1 - y) >= cornerSize)) ||
                        (x < cornerSize && y >= H - cornerSize && ((cornerSize - 1 - x) + (y - (H - cornerSize)) >= cornerSize)) ||
                        (x >= W - cornerSize && y >= H - cornerSize && ((x - (W - cornerSize)) + (y - (H - cornerSize)) >= cornerSize));

                if (inCorner) {
                    img.setRGB(x, y, C_TRANS);
                } else {
                    img.setRGB(x, y, C_WOOD_DARK);
                }
            }
        }

        // ===== 2. 画内层工作台表面 =====
        // 2 像素的深色边 + 内部木色
        for (int y = 2; y < H - 2; y++) {
            for (int x = 2; x < W - 2; x++) {
                // 如果之前是透明角，跳过
                if ((img.getRGB(x, y) & 0xFF_000000) == 0) continue;

                // 用简单的"随机木纹" - 基于像素位置的伪随机
                int woodColor;
                int rnd = ((x * 7 + y * 13 + x * y) % 17 + 17) % 17;
                if (rnd < 3) woodColor = C_WOOD;
                else if (rnd < 10) woodColor = C_WOOD_LIGHT;
                else if (rnd < 14) woodColor = C_WOOD;
                else woodColor = C_WOOD_HILIGHT;

                // 左上角两像素高亮 (仿 MC 物品高光)
                if (x == 2 || y == 2) woodColor = C_WOOD_HILIGHT;
                // 右边一像素暗边
                if (x == W - 3 || y == H - 3) woodColor = C_WOOD_DARK;

                img.setRGB(x, y, woodColor);
            }
        }

        // ===== 3. 画 3x3 物品格 =====
        // 每个物品格 10x10, 间距 2px
        // 总宽度: 10*3 + 2*2 = 34px, 放在中心
        int slotSize = 10;
        int gridStartX = (W - slotSize * 3 - 2 * 2) / 2; // 中心对齐
        int gridStartY = (H - slotSize * 3 - 2 * 2) / 2;

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                int slotX = gridStartX + col * (slotSize + 2);
                int slotY = gridStartY + row * (slotSize + 2);

                // 物品槽背景 (稍微凹陷)
                drawSlot(img, slotX, slotY, slotSize);

                // 物品 (8x8, 在 10x10 里偏 1px)
                int[] itemColors = ITEMS[row * 3 + col];
                drawItemBlock(img, slotX + 1, slotY + 1, 8, itemColors);
            }
        }

        // ===== 4. 画装饰 - 上方一个"铁镐"剪影 (象征受保护物品) =====
        // 在顶部中心画一个小号镐柄 (3像素高的条)
        // 这个细节暗示"某些配方保持原样"
        // 位置: 顶部 5px, 横向居中
        // 实际上用物品格之间的间距做装饰就够了, 不额外加元素避免混乱

        // ===== 5. 输出 =====
        // 64x64 原版
        File out64 = new File("icon-64.png");
        ImageIO.write(img, "PNG", out64);
        System.out.println("Generated: " + out64.getAbsolutePath() + " (" + W + "x" + H + ")");

        // 256x256 放大版 (每像素 -> 4x4)
        BufferedImage img256 = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                img256.setRGB(x, y, img.getRGB(x / 4, y / 4));
            }
        }
        File out256 = new File("icon.png");
        ImageIO.write(img256, "PNG", out256);
        System.out.println("Generated: " + out256.getAbsolutePath() + " (256x256)");
    }

    /**
     * 画一个 10x10 的凹陷物品槽
     * 外两圈暗色, 内部深棕 + 左上高光
     */
    private static void drawSlot(BufferedImage img, int x0, int y0, int size) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int color;
                // 右下阴影边
                if (x == size - 1 || y == size - 1) color = C_SLOT_SHADOW;
                // 左上高光
                else if (x == 0 || y == 0) color = C_SLOT_HILIGHT;
                // 内部深色底
                else color = C_GRID;

                img.setRGB(x0 + x, y0 + y, color);
            }
        }
    }

    /**
     * 画一个 size x size 的方块物品
     * 四个色阶: 左上亮 -> 中心主色 -> 右下阴影
     * 模拟 MC 物品方块的光照感
     */
    private static void drawItemBlock(BufferedImage img, int x0, int y0, int size,
                                      int[] colors) {
        // colors: [暗影, 暗, 主色, 高光]
        int shadow = colors[0];
        int dark = colors[1];
        int main = colors[2];
        int hilight = colors[3];

        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int color = main;

                // 左上 1 像素高光
                if ((x == 0 && y <= 1) || (y == 0 && x <= 1)) color = hilight;
                // 左上角(0,0) 更亮 -> 已经是 hilight
                // 右边 1 像素暗边
                else if (x == size - 1) color = dark;
                // 底边 1 像素阴影
                else if (y == size - 1) color = shadow;
                // 右下角最暗
                else if (x == size - 2 && y == size - 2) color = shadow;
                // 一些随机的微纹理
                else {
                    int r = (x * 3 + y * 7) % 11;
                    if (r < 2) color = hilight;
                    else if (r > 8) color = dark;
                    else color = main;
                }

                img.setRGB(x0 + x, y0 + y, color);
            }
        }
    }
}
