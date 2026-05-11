<template>
  <section class="conversation-panel" :class="{ compact }">
    <div class="panel-header">
      <div>
        <strong>会话列表</strong>
        <p>支持新建与切换历史会话</p>
      </div>
      <div class="header-actions">
        <el-button size="small" @click="aiStore.refreshConversationList()" :disabled="aiStore.pending">刷新</el-button>
        <el-button size="small" type="primary" @click="aiStore.startNewConversation()" :disabled="aiStore.pending">新建</el-button>
      </div>
    </div>

    <div class="conversation-list" v-if="aiStore.conversations.length">
      <div
        v-for="item in aiStore.conversations"
        :key="item.id"
        class="conversation-item"
        :class="{ active: Number(aiStore.conversationId) === Number(item.id), disabled: aiStore.pending }"
        @click="!aiStore.pending && aiStore.switchConversation(item.id)"
      >
        <div class="item-content">
          <strong>{{ item.title || '新会话' }}</strong>
          <span>{{ formatTime(item.updatedTime || item.createdTime) }}</span>
        </div>
        <span
          class="delete-btn"
          title="彻底删除该会话"
          @click.stop="confirmDelete(item)"
        >🗑</span>
      </div>
    </div>

    <div v-else class="empty-block">暂无历史会话，点击右上角可创建新会话</div>
  </section>
</template>

<script setup>
import { ElMessageBox } from 'element-plus';
import { useAiStore } from '@/store/ai';

defineProps({
  compact: {
    type: Boolean,
    default: false
  }
});

const aiStore = useAiStore();

async function confirmDelete(item) {
  if (aiStore.pending) {
    return;
  }
  try {
    await ElMessageBox.confirm(
      `此操作不可逆,30 天内可联系管理员还原,30 天后将物理彻底删除。\n\n确认删除会话「${item.title || '新会话'}」吗?`,
      '彻底删除会话?',
      {
        confirmButtonText: '彻底删除',
        cancelButtonText: '取消',
        type: 'warning'
      }
    );
  } catch {
    return; // 用户取消
  }
  await aiStore.deleteConversation(item.id);
}

function formatTime(value) {
  if (!value) {
    return '刚刚';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  const month = `${date.getMonth() + 1}`.padStart(2, '0');
  const day = `${date.getDate()}`.padStart(2, '0');
  const hour = `${date.getHours()}`.padStart(2, '0');
  const minute = `${date.getMinutes()}`.padStart(2, '0');
  return `${month}-${day} ${hour}:${minute}`;
}
</script>

<style scoped lang="scss">
.conversation-panel {
  display: grid;
  gap: 12px;
  padding: 14px;
  background: #fffaf6;
  border: 1px solid #f4e1d2;
  border-radius: 14px;
}

.conversation-panel.compact {
  padding: 12px;
}

.panel-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}

.panel-header p {
  margin: 6px 0 0;
  font-size: 12px;
  color: #999;
}

.header-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.conversation-list {
  display: grid;
  gap: 10px;
  max-height: 220px;
  overflow-y: auto;
}

.conversation-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 12px;
  text-align: left;
  border: 1px solid #f0e0d4;
  border-radius: 12px;
  background: #fff;
  cursor: pointer;
  transition: all 0.2s ease;
}

.conversation-item.disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.conversation-item:hover {
  border-color: #ffb27a;
  box-shadow: 0 8px 18px rgba(255, 103, 0, 0.08);
}

.conversation-item.active {
  border-color: #ff6700;
  background: #fff4eb;
}

.item-content {
  display: grid;
  gap: 6px;
  flex: 1;
  min-width: 0;
}

.item-content strong {
  color: #333;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.item-content span {
  font-size: 12px;
  color: #999;
}

.delete-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 6px;
  font-size: 14px;
  color: #999;
  cursor: pointer;
  flex-shrink: 0;
  transition: all 0.15s ease;
  user-select: none;
}

.delete-btn:hover {
  background: #fff0ed;
  color: #f56c6c;
}
</style>