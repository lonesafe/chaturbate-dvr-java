<template>
  <el-container class="app-wrapper">
    <el-aside width="220px" class="sidebar">
      <div class="logo">
        <el-icon size="32" color="#409EFF"><VideoCamera /></el-icon>
        <span class="logo-text">Chaturbate DVR</span>
      </div>
      <el-menu
        :default-active="$route.path"
        router
        class="sidebar-menu"
        background-color="#304156"
        text-color="#bfcbd9"
        active-text-color="#409EFF"
      >
        <el-menu-item index="/">
          <el-icon><HomeFilled /></el-icon>
          <span>首页概览</span>
        </el-menu-item>
        <el-menu-item index="/channels">
          <el-icon><UserFilled /></el-icon>
          <span>直播间管理</span>
        </el-menu-item>
        <el-menu-item index="/recordings">
          <el-icon><VideoPlay /></el-icon>
          <span>录制记录</span>
        </el-menu-item>
        <el-menu-item index="/settings">
          <el-icon><Setting /></el-icon>
          <span>系统设置</span>
        </el-menu-item>
      </el-menu>
    </el-aside>
    
    <el-container>
      <el-header class="header">
        <div class="header-right">
          <el-tag v-if="isRecording" type="danger" effect="dark" class="recording-tag">
            <el-icon class="recording-icon"><VideoCamera /></el-icon>
            录制中 {{ recordingCount }} 个直播间
          </el-tag>
          <el-button @click="refreshAll" :loading="refreshing" size="small">
            <el-icon><Refresh /></el-icon> 刷新
          </el-button>
        </div>
      </el-header>
      
      <el-main class="main-content">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useChannelStore } from './stores/channel'
import { ElMessage } from 'element-plus'

const channelStore = useChannelStore()
const refreshing = ref(false)
let refreshTimer = null

const isRecording = computed(() => channelStore.recordingChannels.length > 0)
const recordingCount = computed(() => channelStore.recordingChannels.length)

const refreshAll = async () => {
  refreshing.value = true
  try {
    await Promise.all([
      channelStore.fetchChannels(),
      channelStore.fetchRecordings()
    ])
    ElMessage.success('刷新成功')
  } catch (e) {
    ElMessage.error('刷新失败')
  } finally {
    refreshing.value = false
  }
}

onMounted(() => {
  refreshAll()
  refreshTimer = setInterval(() => {
    channelStore.fetchChannels()
    channelStore.fetchRecordings()
  }, 30000)
})

onUnmounted(() => {
  if (refreshTimer) clearInterval(refreshTimer)
})
</script>

<style scoped>
.app-wrapper {
  height: 100vh;
}

.sidebar {
  background: #304156;
  color: #fff;
}

.logo {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  border-bottom: 1px solid #1f2d3d;
}

.logo-text {
  font-size: 18px;
  font-weight: bold;
  color: #fff;
}

.sidebar-menu {
  border-right: none;
}

.header {
  background: #fff;
  border-bottom: 1px solid #dcdfe6;
  display: flex;
  align-items: center;
  justify-content: flex-end;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 15px;
}

.recording-tag {
  animation: pulse 2s infinite;
}

.recording-icon {
  animation: blink 1s infinite;
  margin-right: 5px;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.8; }
}

@keyframes blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}

.main-content {
  background: #f0f2f5;
  padding: 20px;
  overflow-y: auto;
}
</style>
