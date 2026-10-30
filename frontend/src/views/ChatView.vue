<template>
  <ChatPanel
    ref="chatRef"
    :provider="chatStore.provider"
    :model="chatStore.model"
    :module-key="chatStore.moduleKey"
    @thread-loaded="chatStore.bumpThreads()"
  />
</template>

<script setup>
// 聊天路由入口：持有 ChatPanel 的 ref（ref 不出本组件），
// 消费 AppLayout 经 chatStore 发来的命令（快捷指令/新会话/打开/删除历史会话）。
import { ref, watch, onMounted } from 'vue';
import { useChatStore } from '@/stores/chat';
import ChatPanel from '@/features/chat/ChatPanel.vue';

const chatStore = useChatStore();
const chatRef = ref(null);

function consumeCommand() {
  const cmd = chatStore.lastCommand;
  if (!cmd || !chatRef.value) return;
  chatStore.consumeCommand();
  if (cmd.type === 'send') chatRef.value.send(cmd.payload);
  else if (cmd.type === 'new-chat') chatRef.value.newChat();
  else if (cmd.type === 'open-thread') chatRef.value.openThread(cmd.payload);
  else if (cmd.type === 'delete-thread') {
    if (chatRef.value.threadId === cmd.payload) chatRef.value.newChat();
  }
}

watch(() => chatStore.lastCommand?.seq, consumeCommand);
// 覆盖"在配送页 dispatch 后跳回聊天"的路径：挂载时补执行未消费的命令
onMounted(consumeCommand);
</script>
