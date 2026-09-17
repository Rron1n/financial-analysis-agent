package com.rronin.financialagent.agent;
import java.util.Locale;
import java.util.Set;
/** Exact conversational run controls only. Never interpret order cancellations or quoted requests. */
public final class RunControlIntent {
 private RunControlIntent() {}
 public static boolean status(String text) {
  if(text==null)return false;
  return Set.of("好了吗","完成了吗","做完了吗","当前进度","进度如何","are you done","is it done").contains(text.trim().toLowerCase(Locale.ROOT).replaceAll("[。！？?!]+$","").trim());
 }
 public static boolean stop(String text) {
  if(text==null)return false;
  String value=text.trim().toLowerCase(Locale.ROOT).replaceAll("[。！？?!]+$","").trim();
  return Set.of("停止","停止运行","停止当前运行","结束当前任务","结束当前运行","能直接结束吗","可以直接结束吗","stop","stop this run","stop the current run").contains(value);
 }
}
