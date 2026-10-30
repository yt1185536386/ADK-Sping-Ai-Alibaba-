// 聊天域 store：渠道/模型/模块选择的共享状态 + 跨路由命令桥。
//
// 为什么需要命令桥：路由化之后 ChatPanel 活在子路由里，壳组件（AppLayout）
// 不能再拿它的模板 ref。AppLayout 改为 dispatch 一条命令，ChatView 消费并
// 调用 ChatPanel 暴露的方法。命令消费后即清空，保证：
// - 在聊天页 dispatch → watch 立即执行
// - 在配送页 dispatch → 跳回聊天页后由 ChatView onMounted 补执行（不丢命令）
// - 反复进出聊天页不会重复执行历史命令
import { defineStore } from 'pinia';

let seq = 0;

export const useChatStore = defineStore('chat', {
  state: () => ({
    provider: '',
    model: '',
    moduleKey: '',
    threadsVersion: 0, // ChatPanel 载入/新增会话后 +1，AppLayout 监听以刷新历史列表
    lastCommand: null  // { seq, type: 'send'|'new-chat'|'open-thread'|'delete-thread', payload }
  }),
  actions: {
    dispatch(type, payload) {
      this.lastCommand = { seq: ++seq, type, payload };
    },
    /** ChatView 消费命令后调用，防止重新挂载时重放 */
    consumeCommand() {
      this.lastCommand = null;
    },
    bumpThreads() {
      this.threadsVersion++;
    }
  }
});
