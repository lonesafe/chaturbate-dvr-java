<template>
  <div class="home">
    <!-- 统计卡片 -->
    <el-row :gutter="20" class="stats-row">
      <el-col :span="6" v-for="stat in statCards" :key="stat.key">
        <el-card class="stat-card" :body-style="{ padding: '20px' }">
          <div class="stat-icon" :style="{ background: stat.color }">
            <el-icon size="24" color="#fff"><component :is="stat.icon" /></el-icon>
          </div>
          <div class="stat-info">
            <div class="stat-value" :style="{ color: stat.color }">{{ stat.value }}</div>
            <div class="stat-label">{{ stat.label }}</div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 直播间状态图表 -->
    <el-row :gutter="20" class="chart-row">
      <el-col :span="12">
        <el-card>
          <template #header>
            <div class="card-header">
              <span>📊 直播间状态分布</span>
            </div>
          </template>
          <div class="status-chart">
            <div 
              v-for="item in statusData" 
              :key="item.status"
              class="status-bar-item"
            >
              <div class="status-label">
                <el-tag :type="item.type" size="small">{{ item.label }}</el-tag>
              </div>
              <el-progress 
                :percentage="item.percentage" 
                :color="item.color"
                :stroke-width="20"
                :show-text="true"
              />
              <div class="status-count">{{ item.count }} 个</div>
            </div>
          </div>
        </el-card>
      </el-col>
      
      <el-col :span="12">
        <el-card>
          <template #header>
            <div class="card-header">
              <span>🔴 正在录制</span>
            </div>
          </template>
          <div v-if="recordingChannels.length === 0" class="empty-text">
            <el-empty description="暂无录制中的直播间" />
          </div>
          <div v-else class="recording-list">
            <div 
              v-for="channel in recordingChannels" 
              :key="channel.id"
              class="recording-item"
            >
              <div class="recording-avatar">
                <el-avatar :size="40" :style="{ background: '#F56C6C' }">
                  <el-icon><VideoCamera /></el-icon>
                </el-avatar>
              </div>
              <div class="recording-info">
                <div class="recording-name">{{ channel.displayName || channel.username }}</div>
                <div class="recording-meta">
                  <el-tag type="danger" size="small" effect="dark">录制中</el-tag>
                  <span class="recording-time">{{ formatTime(channel.lastCheckTime) }}</span>
                </div>
              </div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useChannelStore } from '../stores/channel'
import dayjs from 'dayjs'
import relativeTime from 'dayjs/plugin/relativeTime'
import 'dayjs/locale/zh-cn'

dayjs.extend(relativeTime)
dayjs.locale('zh-cn')

const store = useChannelStore()

const statCards = computed(() => [
  { key: 'total', label: '总直播间', value: store.stats.total, icon: 'Monitor', color: '#409EFF' },
  { key: 'online', label: '在线直播', value: store.stats.online, icon: 'VideoPlay', color: '#67C23A' },
  { key: 'recording', label: '录制中', value: store.stats.recording, icon: 'VideoCamera', color: '#F56C6C' },
  { key: 'offline', label: '离线', value: store.stats.offline, icon: 'CircleClose', color: '#909399' }
])

const statusData = computed(() => {
  const total = store.stats.total || 1
  return [
    { status: 'online', label: '在线', count: store.stats.online, percentage: Math.round(store.stats.online / total * 100), color: '#67C23A', type: 'success' },
    { status: 'recording', label: '录制中', count: store.stats.recording, percentage: Math.round(store.stats.recording / total * 100), color: '#F56C6C', type: 'danger' },
    { status: 'offline', label: '离线', count: store.stats.offline, percentage: Math.round(store.stats.offline / total * 100), color: '#909399', type: 'info' },
    { status: 'private', label: '私密', count: store.stats.private, percentage: Math.round(store.stats.private / total * 100), color: '#E6A23C', type: 'warning' }
  ]
})

const recordingChannels = computed(() => store.recordingChannels)

const formatTime = (date) => date ? dayjs(date).fromNow() : '-'
</script>

<style scoped>
.stats-row {
  margin-bottom: 20px;
}

.stat-card {
  display: flex;
  align-items: center;
  gap: 15px;
}

.stat-icon {
  width: 50px;
  height: 50px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.stat-info {
  flex: 1;
}

.stat-value {
  font-size: 24px;
  font-weight: bold;
  line-height: 1;
}

.stat-label {
  font-size: 12px;
  color: #909399;
  margin-top: 5px;
}

.chart-row {
  margin-bottom: 20px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.status-chart {
  padding: 10px 0;
}

.status-bar-item {
  display: flex;
  align-items: center;
  gap: 15px;
  margin-bottom: 20px;
}

.status-label {
  width: 80px;
  text-align: right;
}

.status-bar-item :deep(.el-progress) {
  flex: 1;
}

.status-count {
  width: 60px;
  text-align: right;
  color: #606266;
  font-size: 14px;
}

.recording-list {
  max-height: 300px;
  overflow-y: auto;
}

.recording-item {
  display: flex;
  align-items: center;
  gap: 15px;
  padding: 15px;
  border-bottom: 1px solid #ebeef5;
}

.recording-item:last-child {
  border-bottom: none;
}

.recording-info {
  flex: 1;
}

.recording-name {
  font-weight: bold;
  margin-bottom: 5px;
}

.recording-meta {
  display: flex;
  align-items: center;
  gap: 10px;
}

.recording-time {
  font-size: 12px;
  color: #909399;
}

.empty-text {
  padding: 40px 0;
  text-align: center;
  color: #909399;
}
</style>
