package com.rronin.financialagent.agent;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class RunControlIntentTest {
 @Test void onlyExactRunControlCommandsAreRecognized(){
  assertThat(RunControlIntent.stop("能直接结束吗？")).isTrue();
  assertThat(RunControlIntent.stop("停止当前运行")).isTrue();
  assertThat(RunControlIntent.stop("停止 TE 的限价订单")).isFalse();
  assertThat(RunControlIntent.stop("请解释‘能直接结束吗’是什么意思")).isFalse();
  assertThat(RunControlIntent.stop("分析 stop loss 策略")).isFalse();
 }
}
