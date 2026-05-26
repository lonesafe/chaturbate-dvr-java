<template>
  <div class="recordings-page">
    <el-card>
      <template #header>
        <div class="page-header">
          <span>🎬 录制记录</span>
          <div class="header-actions">
            <el-radio-group v-model="filter" size="small">
              <el-radio-button label="all">全部</el-radio-button>
              <el-radio-button label="recording">录制中</el-radio-button>
              <el-radio-button label="completed">已完成</el-radio-button>
              <el-radio-button label="failed">失败</el-radio-button>
            </el-radio-group>
          </div>
        </div>
      </template>

      <el-table :data="filteredRecordings" v-loading="loading" style="width: 100%">
        <el-table-column type="index" width="50" />
        <el-table-column label="主播" width="150">
          <template #default="{ row }">
            <div class="channel-cell">
              <el-avatar :size="32" :style="{ background: '#409EFF' }">
                {{ row.channelUsername.charAt(0).toUpperCase() }}
              </el-avatar>
              <span class="channel-name">{{ row.channelUsername }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="开始时间" width="180">
          <template #default="{ row }">
            <div class="time-cell">
              <el-icon><Clock /></el-icon>
              {{ formatDate(row.startTime) }}
            </div>
          </template>
        </el-table-column>
        <el-table-column label="结束时间" width="180">
          <template #default="{ row }">
            <div class="time-cell" v-if="row.endTime">
              <el-icon><Timer /></el-icon>
              {{ formatDate(row.endTime) }}
            </div>
            <el-tag v-else type="info" size="small">进行中</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="时长" width="100">
          <template #default="{ row }">
            <el-tag :type="row.durationSeconds ? 'success' : 'info'" size="small">
              {{ formatDuration(row.durationSeconds) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="quality" label="质量" width="80">
          <template #default="{ row }">
            <el-tag type="primary" size="small">{{ row.quality }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)" size="small" effect="dark">
              <el-icon v-if="row.status === 'recording'" class="recording-icon"><VideoCamera /></el-icon>
              {{ getStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="文件大小" width="120">
          <template #default="{ row }">
            {{ formatFileSize(row.fileSize) }}
          </template>
        </el-table-column>
        <el-table-column label="文件路径" min-width="200">
          <template #default="{ row }">
            <el-tooltip :content="row.filePath" placement="top">
              <span class="file-path">{{ row.filePath }}</span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button-group>
              <el-button 
                v-if="row.filePath"
                size="small"
                @click="openFile(row.filePath)"
              >
                打开
              </el-button>
              <el-button type="danger" size="small" @click="deleteRecording(row)">
                删除
              </el-button>
            </el-button-group>
          </template>
        </el-table-column>
      </el-table>

      <!-- 空状态 -->
      <el-empty v-if="filteredRecordings.length === 0" description="暂无录制记录" />
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useChannelStore } from '../stores/channel'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'

const store = useChannelStore()
const loading = ref(false)
const filter = ref('all')

const filteredRecordings = computed(() => {
  let list = [...store.recordings]
  
  if (filter.value === 'recording') {
    list = list.filter(r => r.status === 'recording')
  } else if (filter.value === 'completed') {
    list = list.filter(r => r.status === 'completed')
  } else if (filter.value === 'failed') {
    list = list.filter(r => r.status === 'failed')
  }
  
  return list.sort((a, b) => new Date(b.startTime) - new Date(a.startTime))
})

const formatDate = (date) => date ? dayjs(date).format('YYYY-MM-DD HH:mm:ss') : '-'

const formatDuration = (seconds) => {
  if (!seconds) return '-'
  const hours = Math.floor(seconds / 3600)
  const mins = Math.floor((seconds % 3600) / 60)
  const secs = seconds % 60
  if (hours > 0) {
    return `${hours}时${mins}分`
  }
  return `${mins}分${secs}秒`
}

const formatFileSize = (bytes) => {
  if (!bytes) return '-'
  const units = ['B', 'KB', 'MB', 'GB']
  let size = bytes
  let i = 0
  while (size >= 1024 && i < units.length - 1) {
    size /= 1024
    i++
  }
  return `${size.toFixed(2)} ${units[i]}`
}

const getStatusType = (status) => {
  const map = { recording: 'danger', completed: 'success', failed: 'warning' }
  return map[status] || 'info'
}

const getStatusText = (status) => {
  const map = { recording: '录制中', completed: '已完成', failed: '失败' }
  return map[status] || status
}

const openFile = (path) => {
  // 在浏览器中无法直接打开本地文件，可以复制路径
  ElMessage.info(`文件路径: ${path}`)
}

const deleteRecording = async (recording) => {
  try {
    await ElMessageBox.confirm('确定删除该录制记录吗？', '确认删除', { type: 'warning' })
    // TODO: 调用删除API
    ElMessage.success('删除成功')
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('删除失败')
  }
}
</script>

<style scoped>
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.channel-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}

.channel-name {
  font-weight: bold;
}

.time-cell {
  display: flex;
  align-items: center;
  gap: 5px;
  color: #606266;
}

.file-path {
  color: #409EFF;
  cursor: pointer;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: block;
  max-width: 200px;
}

.recording-icon {
  animation: blink 1s infinite;
  margin-right: 3px;
}

@keyframes blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}
</style>
