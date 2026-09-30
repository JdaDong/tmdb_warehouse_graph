package com.tmdbwh.offline;

import java.util.Arrays;
import java.util.List;
import scala.collection.JavaConverters;
import scala.collection.Seq;

/**
 * Java → Scala 集合转换。
 *
 * <p>Spark 的 Dataset API 有多个重载：接受 Scala {@link Seq} 的（{@code join(ds, Seq, String)}、
 * {@code select(Seq)}）与接受 Java {@link List} 的（{@code join(ds, List, String)}）。
 * 从 Java 调用时容易匹配到 Scala 版本而编译失败，因此统一由这里转换，避免各处自己猜重载。
 */
public final class ScalaSeqs {

    private ScalaSeqs() {}

    /** 转成 Scala Seq。 */
    public static Seq<String> of(String... values) {
        return JavaConverters.asScalaBuffer(Arrays.asList(values)).toSeq();
    }

    /** 转成 Scala Seq。 */
    public static Seq<String> from(List<String> values) {
        return JavaConverters.asScalaBuffer(values).toSeq();
    }
}
