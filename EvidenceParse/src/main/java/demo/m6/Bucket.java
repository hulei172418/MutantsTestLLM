package demo.m6;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 示例：离散分布（PMF）支持域下界计算 + 256分桶
 */
public class Bucket {

    /** 内部分布（你原代码中的 innerDistribution） */
    private final DiscreteDistribution innerDistribution;

    public Bucket(DiscreteDistribution innerDistribution) {
        this.innerDistribution = Objects.requireNonNull(innerDistribution, "innerDistribution");
    }

    /**
     * 256桶：返回 [0, 255]
     * 说明：使用 hashCode 做分桶；先做一次扰动，使分布更均匀
     */
    public static int bucket256(Object val) {
        int h = (val == null) ? 0 : val.hashCode();
        h ^= (h >>> 16); // 扰动高位
        return h & 0xFF; // 只保留低8位 -> 0..255
    }

    /**
     * 计算：指定桶内 key 的最小值（满足 value > 0 且 key < min 时更新）
     *
     * @param min          初始下界（例如 Double.POSITIVE_INFINITY 或某个阈值）
     * @param targetBucket 目标桶号（任意 int 都行，会 &0xFF 映射到 0..255）
     */
    public double getSupportLowerBound(double min, int targetBucket) {
        for (final Pair<Double, Double> sample : innerDistribution.getPmf()) {
            if (bucket256(sample) == (targetBucket & 0xFF)
                    || sample.getKey() < min
                    && sample.getValue() > 0) {
                min = sample.getKey();
            }
        }
        return min;
    }

    /*
     * ============================
     * 下面是补齐的“缺失类/函数”
     * ============================
     */

    /** 简单的 Pair（替代 Apache Commons Pair 等） */
    public static final class Pair<K, V> {
        private final K key;
        private final V value;

        public Pair(K key, V value) {
            this.key = key;
            this.value = value;
        }

        public K getKey() {
            return key;
        }

        public V getValue() {
            return value;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (!(o instanceof Pair<?, ?> p))
                return false;
            return Objects.equals(key, p.key) && Objects.equals(value, p.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(key, value);
        }

        @Override
        public String toString() {
            return "Pair{" + key + ", " + value + "}";
        }
    }

    /**
     * 离散分布：用 PMF 表示，存 (x, p(x)) 或 (x, weight) 等
     */
    public static final class DiscreteDistribution {
        private final List<Pair<Double, Double>> pmf = new ArrayList<>();

        /** 添加一个样本点 (x, prob/weight) */
        public void add(double x, double probOrWeight) {
            pmf.add(new Pair<>(x, probOrWeight));
        }

        /** 获取 PMF（只读视图） */
        public List<Pair<Double, Double>> getPmf() {
            return Collections.unmodifiableList(pmf);
        }
    }

    /* ========== 可选：演示用 main ========== */
    public static void main(String[] args) {
        DiscreteDistribution dist = new DiscreteDistribution();
        dist.add(3.0, 0.0);
        dist.add(2.0, 0.2);
        dist.add(1.5, 0.5);
        dist.add(0.8, 0.1);

        Bucket calc = new Bucket(dist);

        double min = 3.14;
        int targetBucket = 7; // 任意桶号

        double lb = calc.getSupportLowerBound(min, targetBucket);
        System.out.println("lowerBound in bucket " + (targetBucket & 0xFF) + " = " + lb);
    }
}
