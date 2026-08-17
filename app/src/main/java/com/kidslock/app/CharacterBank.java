package com.kidslock.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 五年级水平汉字题库。
 * 每道题：展示一个汉字，从4个拼音选项中选出正确读音。
 */
public class CharacterBank {

    public static class CharEntry {
        public final String character;
        public final String pinyin;

        CharEntry(String character, String pinyin) {
            this.character = character;
            this.pinyin = pinyin;
        }
    }

    private static final CharEntry[] BANK = {
        new CharEntry("誉", "yù"),
        new CharEntry("邦", "bāng"),
        new CharEntry("餐", "cān"),
        new CharEntry("沧", "cāng"),
        new CharEntry("巢", "cháo"),
        new CharEntry("撑", "chēng"),
        new CharEntry("炽", "chì"),
        new CharEntry("储", "chǔ"),
        new CharEntry("簇", "cù"),
        new CharEntry("岱", "dài"),
        new CharEntry("涤", "dí"),
        new CharEntry("淀", "diàn"),
        new CharEntry("鼎", "dǐng"),
        new CharEntry("锻", "duàn"),
        new CharEntry("恩", "ēn"),
        new CharEntry("伐", "fá"),
        new CharEntry("仿", "fǎng"),
        new CharEntry("绯", "fēi"),
        new CharEntry("蜂", "fēng"),
        new CharEntry("赋", "fù"),
        new CharEntry("钢", "gāng"),
        new CharEntry("隔", "gé"),
        new CharEntry("弧", "hú"),
        new CharEntry("焕", "huàn"),
        new CharEntry("惶", "huáng"),
        new CharEntry("慧", "huì"),
        new CharEntry("寂", "jì"),
        new CharEntry("驾", "jià"),
        new CharEntry("歼", "jiān"),
        new CharEntry("茧", "jiǎn"),
        new CharEntry("俭", "jiǎn"),
        new CharEntry("嚼", "jué"),
        new CharEntry("襟", "jīn"),
        new CharEntry("钧", "jūn"),
        new CharEntry("咖", "kā"),
        new CharEntry("恳", "kěn"),
        new CharEntry("叩", "kòu"),
        new CharEntry("窟", "kū"),
        new CharEntry("框", "kuàng"),
        new CharEntry("溃", "kuì"),
        new CharEntry("辣", "là"),
        new CharEntry("揽", "lǎn"),
        new CharEntry("烙", "lào"),
        new CharEntry("蕾", "lěi"),
        new CharEntry("凛", "lǐn"),
        new CharEntry("拎", "līn"),
        new CharEntry("隆", "lóng"),
        new CharEntry("搂", "lǒu"),
        new CharEntry("鹿", "lù"),
        new CharEntry("履", "lǚ"),
        new CharEntry("峦", "luán"),
        new CharEntry("掠", "lüè"),
        new CharEntry("脉", "mài"),
        new CharEntry("盲", "máng"),
        new CharEntry("贸", "mào"),
        new CharEntry("朦", "méng"),
        new CharEntry("弥", "mí"),
        new CharEntry("觅", "mì"),
        new CharEntry("蔑", "miè"),
        new CharEntry("铭", "míng"),
        new CharEntry("陌", "mò"),
        new CharEntry("钮", "niǔ"),
        new CharEntry("诺", "nuò"),
        new CharEntry("畔", "pàn"),
        new CharEntry("抛", "pāo"),
        new CharEntry("喷", "pēn"),
        new CharEntry("鹏", "péng"),
        new CharEntry("聘", "pìn"),
        new CharEntry("魄", "pò"),
        new CharEntry("剖", "pōu"),
        new CharEntry("祈", "qí"),
        new CharEntry("契", "qì"),
        new CharEntry("歉", "qiàn"),
        new CharEntry("腔", "qiāng"),
        new CharEntry("俏", "qiào"),
        new CharEntry("窃", "qiè"),
        new CharEntry("钦", "qīn"),
        new CharEntry("躯", "qū"),
        new CharEntry("趋", "qū"),
        new CharEntry("雀", "què"),
        new CharEntry("嚷", "rǎng"),
        new CharEntry("绕", "rào"),
        new CharEntry("刃", "rèn"),
        new CharEntry("戎", "róng"),
        new CharEntry("锐", "ruì"),
        new CharEntry("辱", "rǔ"),
        new CharEntry("嫂", "sǎo"),
        new CharEntry("莎", "shā"),
        new CharEntry("韶", "sháo"),
        new CharEntry("誓", "shì"),
        new CharEntry("舒", "shū"),
        new CharEntry("鼠", "shǔ"),
        new CharEntry("栓", "shuān"),
        new CharEntry("硕", "shuò"),
        new CharEntry("撕", "sī"),
        new CharEntry("肆", "sì"),
        new CharEntry("颂", "sòng"),
        new CharEntry("俗", "sú"),
        new CharEntry("遂", "suì"),
        new CharEntry("贪", "tān"),
        new CharEntry("毯", "tǎn"),
        new CharEntry("滔", "tāo"),
        new CharEntry("藤", "téng"),
        new CharEntry("恬", "tián"),
        new CharEntry("廷", "tíng"),
        new CharEntry("凸", "tū"),
        new CharEntry("颓", "tuí"),
        new CharEntry("褪", "tuì"),
        new CharEntry("婉", "wǎn"),
        new CharEntry("亡", "wáng"),
        new CharEntry("枉", "wǎng"),
        new CharEntry("巍", "wēi"),
        new CharEntry("蔚", "wèi"),
        new CharEntry("涡", "wō"),
        new CharEntry("污", "wū"),
        new CharEntry("熄", "xī"),
        new CharEntry("狭", "xiá"),
        new CharEntry("弦", "xián"),
        new CharEntry("馅", "xiàn"),
        new CharEntry("翔", "xiáng"),
        new CharEntry("卸", "xiè"),
        new CharEntry("械", "xiè"),
        new CharEntry("辛", "xīn"),
        new CharEntry("锈", "xiù"),
        new CharEntry("崖", "yá"),
        new CharEntry("宴", "yàn"),
        new CharEntry("秧", "yāng"),
        new CharEntry("妖", "yāo"),
        new CharEntry("钥", "yuè"),
        new CharEntry("蕴", "yùn"),
        new CharEntry("咋", "zǎ"),
        new CharEntry("榨", "zhà"),
        new CharEntry("瞻", "zhān"),
        new CharEntry("障", "zhàng"),
        new CharEntry("哲", "zhé"),
        new CharEntry("轴", "zhóu"),
        new CharEntry("铸", "zhù"),
        new CharEntry("拽", "zhuài"),
        new CharEntry("篆", "zhuàn"),
        new CharEntry("桩", "zhuāng"),
        new CharEntry("浊", "zhuó"),
        new CharEntry("滋", "zī"),
        new CharEntry("棕", "zōng"),
        new CharEntry("奏", "zòu"),
    };

    private final Random random = new Random();
    private final List<Integer> recentIndices = new ArrayList<>();
    private static final int RECENT_MEMORY = 10;

    /**
     * 随机取一道题（尽量不与最近用过的重复）。
     */
    public CharEntry getRandomCharacter() {
        int idx;
        int attempts = 0;
        do {
            idx = random.nextInt(BANK.length);
            attempts++;
        } while (recentIndices.contains(idx) && attempts < 20);
        recentIndices.add(idx);
        if (recentIndices.size() > RECENT_MEMORY) {
            recentIndices.remove(0);
        }
        return BANK[idx];
    }

    /**
     * 取干扰项：3个与正确拼音不同的选项。
     */
    public List<String> getDistractors(String correctPinyin, int count) {
        Set<String> used = new HashSet<>();
        used.add(correctPinyin);
        List<String> result = new ArrayList<>();
        int attempts = 0;
        while (result.size() < count && attempts < 100) {
            int idx = random.nextInt(BANK.length);
            String py = BANK[idx].pinyin;
            if (!used.contains(py)) {
                used.add(py);
                result.add(py);
            }
            attempts++;
        }
        // 万一不够，补几个通用拼音
        while (result.size() < count) {
            result.add("n/a");
        }
        return result;
    }

    /**
     * 生成一道完整题目：正确答案 + 3个干扰项，打乱顺序。
     * 返回4个选项，其中1个是正确答案。
     */
    public List<String> getOptions(String correctPinyin) {
        List<String> options = new ArrayList<>();
        options.add(correctPinyin);
        options.addAll(getDistractors(correctPinyin, 3));
        Collections.shuffle(options, random);
        return options;
    }
}
