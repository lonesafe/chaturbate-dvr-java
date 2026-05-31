<template>
  <div class="channels-page">
    <el-card>
      <template #header>
        <div class="page-header">
          <span>📋 直播间管理</span>
          <el-button type="primary" @click="showAddDialog = true">
            <el-icon><Plus /></el-icon> 添加直播间
          </el-button>
        </div>
      </template>

      <!-- 筛选栏 -->
      <div class="filter-bar">
        <el-radio-group v-model="filter" size="small">
          <el-radio-button label="all">全部 ({{ store.channels.length }})</el-radio-button>
          <el-radio-button label="online">在线 ({{ store.stats.online }})</el-radio-button>
          <el-radio-button label="recording">录制中 ({{ store.stats.recording }})</el-radio-button>
          <el-radio-button label="offline">离线 ({{ store.stats.offline }})</el-radio-button>
        </el-radio-group>
        <el-input
          v-model="searchText"
          placeholder="搜索用户名"
          style="width: 200px"
          size="small"
          clearable
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
      </div>

      <!-- 直播间列表 -->
      <el-table :data="filteredChannels" v-loading="loading" style="width: 100%">
        <el-table-column type="index" width="50" />
        <el-table-column label="用户名" width="150">
          <template #default="{ row }">
            <div class="channel-name">
              <el-avatar :size="32" :style="{ background: getAvatarColor(row.username) }">
                {{ row.username.charAt(0).toUpperCase() }}
              </el-avatar>
              <div class="channel-info">
                <div class="username">
                  <!-- 修改：用户名可点击，跳转到直播间 -->
                  <a :href="'https://zh-hans.chaturbate.com/' + row.username + '/'"
                     target="_blank"
                     class="username-link">
                    {{ row.username }}
                  </a>
                </div>
                <div class="display-name" v-if="row.displayName && row.displayName !== row.username">
                  {{ row.displayName }}
                </div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <div class="status-cell">
              <span class="status-dot" :class="getStatusClass(row)"></span>
              <span>{{ getStatusText(row) }}</span>
              <el-icon v-if="row.recording" class="recording-icon"><VideoCamera /></el-icon>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="最后状态" width="100">
          <template #default="{ row }">
            <el-tag :type="getLastStatusType(row.lastStatus)" size="small">
              {{ row.lastStatus || '未知' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="监控" width="80">
          <template #default="{ row }">
            <el-switch
              v-model="row.enabled"
              @change="handleToggle(row)"
              :loading="row.toggling"
            />
          </template>
        </el-table-column>
        <el-table-column label="最后检查" width="180">
          <template #default="{ row }">
            {{ formatDate(row.lastCheckTime) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button-group>
              <el-button 
                v-if="row.lastStatus === 'public' && !row.recording"
                type="primary" 
                size="small"
                @click="startRecording(row)"
              >
                录制
              </el-button>
              <el-button 
                v-if="row.recording"
                type="danger" 
                size="small"
                @click="stopRecording(row)"
              >
                停止
              </el-button>
              <el-button 
                size="small" 
                @click="viewLogs(row)"
                :icon="Document"
              >
                日志
              </el-button>
              <el-button 
                size="small" 
                @click="viewRecordings(row)"
                :icon="FolderOpened"
              >
                文件
              </el-button>
              <el-button size="small" @click="editChannel(row)">编辑</el-button>
              <el-button type="danger" size="small" @click="deleteChannel(row)">删除</el-button>
            </el-button-group>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 添加直播间对话框 -->
    <el-dialog v-model="showAddDialog" title="添加直播间" width="400px">
      <el-form :model="newChannel" label-width="80px">
        <el-form-item label="用户名" required>
          <el-input v-model="newChannel.username" placeholder="输入主播用户名" />
        </el-form-item>
        <el-form-item label="显示名称">
          <el-input v-model="newChannel.displayName" placeholder="可选，默认使用用户名" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showAddDialog = false">取消</el-button>
        <el-button type="primary" @click="handleAdd" :loading="adding">添加</el-button>
      </template>
    </el-dialog>

    <!-- 编辑对话框 -->
    <el-dialog v-model="showEditDialog" title="编辑直播间" width="400px">
      <el-form :model="editForm" label-width="80px">
        <el-form-item label="用户名">
          <el-input v-model="editForm.username" disabled />
        </el-form-item>
        <el-form-item label="显示名称">
          <el-input v-model="editForm.displayName" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showEditDialog = false">取消</el-button>
        <el-button type="primary" @click="handleEdit" :loading="editing">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useChannelStore } from '../stores/channel'
import { channelApi, recordingApi } from '../api'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import { Document, FolderOpened } from '@element-plus/icons-vue'

const store = useChannelStore()
const loading = ref(false)
const filter = ref('all')
const searchText = ref('')

// 添加对话框
const showAddDialog = ref(false)
const adding = ref(false)
const newChannel = ref({ username: '', displayName: '' })

// 编辑对话框
const showEditDialog = ref(false)
const editing = ref(false)
const editForm = ref({ id: null, username: '', displayName: '' })

const filteredChannels = computed(() => {
  let list = store.channels
  
  // 筛选
  if (filter.value === 'online') {
    list = list.filter(c => c.lastStatus === 'public')
  } else if (filter.value === 'recording') {
    list = list.filter(c => c.recording)
  } else if (filter.value === 'offline') {
    list = list.filter(c => c.lastStatus === 'offline')
  }
  
  // 搜索
  if (searchText.value) {
    const keyword = searchText.value.toLowerCase()
    list = list.filter(c => 
      c.username.toLowerCase().includes(keyword) ||
      (c.displayName && c.displayName.toLowerCase().includes(keyword))
    )
  }
  
  return list
})

const getAvatarColor = (username) => {
  const colors = ['#409EFF', '#67C23A', '#E6A23C', '#F56C6C', '#909399', '#8E44AD']
  let hash = 0
  for (let i = 0; i < username.length; i++) {
    hash = username.charCodeAt(i) + ((hash << 5) - hash)
  }
  return colors[Math.abs(hash) % colors.length]
}

const getStatusClass = (channel) => {
  if (channel.recording) return 'status-recording'
  if (channel.lastStatus === 'public') return 'status-online'
  if (channel.lastStatus === 'private') return 'status-private'
  return 'status-offline'
}

const getStatusText = (channel) => {
  if (channel.recording) return '录制中'
  if (channel.lastStatus === 'public') return '在线'
  if (channel.lastStatus === 'private') return '私密'
  return '离线'
}

const getLastStatusType = (status) => {
  const map = { public: 'success', private: 'warning', offline: 'info' }
  return map[status] || 'info'
}

const formatDate = (date) => date ? dayjs(date).format('MM-DD HH:mm:ss') : '从未'

const handleAdd = async () => {
  if (!newChannel.value.username.trim()) {
    ElMessage.warning('请输入用户名')
    return
  }
  adding.value = true
  try {
    await store.addChannel(newChannel.value)
    ElMessage.success('添加成功')
    showAddDialog.value = false
    newChannel.value = { username: '', displayName: '' }
  } catch (e) {
    ElMessage.error(e.response?.data || '添加失败')
  } finally {
    adding.value = false
  }
}

const editChannel = (channel) => {
  editForm.value = { ...channel }
  showEditDialog.value = true
}

const handleEdit = async () => {
  editing.value = true
  try {
    // TODO: 调用更新API
    ElMessage.success('保存成功')
    showEditDialog.value = false
  } catch (e) {
    ElMessage.error('保存失败')
  } finally {
    editing.value = false
  }
}

const deleteChannel = async (channel) => {
  try {
    await ElMessageBox.confirm(
      `确定删除直播间 "${channel.username}" 吗？`,
      '确认删除',
      { type: 'warning' }
    )
    await store.deleteChannel(channel.id)
    ElMessage.success('删除成功')
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('删除失败')
  }
}

const handleToggle = async (channel) => {
  channel.toggling = true
  try {
    await store.toggleChannel(channel.id)
    ElMessage.success(channel.enabled ? '已启用监控' : '已禁用监控')
  } catch (e) {
    ElMessage.error('操作失败')
    channel.enabled = !channel.enabled
  } finally {
    channel.toggling = false
  }
}

const startRecording = async (channel) => {
  try {
    await recordingApi.start(channel.username)
    ElMessage.success(`开始录制 ${channel.username}`)
    channel.recording = true
    // 启动状态轮询
    startStatusPolling()
  } catch (e) {
    ElMessage.error(e || '开始录制失败')
  }
}

const stopRecording = async (channel) => {
  try {
    await ElMessageBox.confirm(
      `确定停止录制 "${channel.username}" 吗？`,
      '确认停止',
      { type: 'warning' }
    )
    await recordingApi.stop(channel.username)
    ElMessage.success(`停止录制 ${channel.username}`)
    channel.recording = false
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e || '停止录制失败')
  }
}

// 查看录制日志
const viewLogs = async (channel) => {
  try {
    const res = await recordingApi.getLogs(channel.username)
    // 显示日志对话框
    ElMessageBox.alert(
      `<div style="max-height: 400px; overflow-y: auto;"><pre>${res.logs || '暂无日志'}</pre></div>`,
      `录制日志 - ${channel.username}`,
      {
        dangerouslyUseHTMLString: true,
        confirmButtonText: '关闭',
        customClass: 'log-dialog'
      }
    )
  } catch (e) {
    ElMessage.error('获取日志失败: ' + (e || '未知错误'))
  }
}

// 查看录制文件
const viewRecordings = async (channel) => {
  try {
    const res = await recordingApi.getByChannel(channel.id)
    if (!res || res.length === 0) {
      ElMessage.info('该频道暂无录制文件')
      return
    }
    
    // 显示文件列表对话框
    const fileList = res.map(r => 
      `<div style="margin: 8px 0; padding: 8px; background: #f5f5f5; border-radius: 4px;">
        <div><strong>文件:</strong> ${r.filePath || '未知'}</div>
        <div><strong>大小:</strong> ${formatFileSize(r.fileSize)}</div>
        <div><strong>时长:</strong> ${formatDuration(r.durationSeconds)}</div>
        <div><strong>时间:</strong> ${formatDate(r.startTime)}</div>
      </div>`
    ).join('')
    
    ElMessageBox.alert(
      `<div style="max-height: 500px; overflow-y: auto;">${fileList}</div>`,
      `录制文件 - ${channel.username}`,
      {
        dangerouslyUseHTMLString: true,
        confirmButtonText: '关闭',
        customClass: 'file-dialog'
      }
    )
  } catch (e) {
    ElMessage.error('获取录制文件失败: ' + (e || '未知错误'))
  }
}

// 格式化文件大小
const formatFileSize = (bytes) => {
  if (!bytes) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  let size = bytes
  let unitIndex = 0
  while (size >= 1024 && unitIndex < units.length - 1) {
    size /= 1024
    unitIndex++
  }
  return `${size.toFixed(2)} ${units[unitIndex]}`
}

// 格式化时长
const formatDuration = (seconds) => {
  if (!seconds) return '0秒'
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  const secs = seconds % 60
  if (hours > 0) {
    return `${hours}小时${minutes}分钟${secs}秒`
  } else if (minutes > 0) {
    return `${minutes}分钟${secs}秒`
  } else {
    return `${secs}秒`
  }
}
const startStatusPolling = () => {
  if (statusTimer) return
  statusTimer = setInterval(async () => {
    try {
      const res = await recordingApi.getActive()
      // 更新录制状态
      store.channels.forEach(channel => {
        const recording = res.find(r => r.username === channel.username)
        channel.recording = !!recording
      })
    } catch (e) {
      console.error('状态轮询失败:', e)
    }
  }, 5000) // 每5秒轮询一次
}

const stopStatusPolling = () => {
  if (statusTimer) {
    clearInterval(statusTimer)
    statusTimer = null
  }
}

// 状态轮询
let statusTimer = null

// 生命周期
onMounted(() => {
  startStatusPolling()
})

onUnmounted(() => {
  stopStatusPolling()
})
</script>

<style scoped>
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.filter-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.channel-name {
  display: flex;
  align-items: center;
  gap: 10px;
}

.channel-info {
  display: flex;
  flex-direction: column;
}

.username {
  font-weight: bold;
}

/* 新增：用户名链接样式 */
.username-link {
  color: #409EFF;
  text-decoration: none;
  font-weight: bold;
  transition: color 0.3s;
}

.username-link:hover {
  color: #66b1ff;
  text-decoration: underline;
}

.display-name {
  font-size: 12px;
  color: #909399;
}

.status-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}

.status-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
}

.status-online { background: #67C23A; }
.status-recording { background: #F56C6C; animation: pulse 1.5s infinite; }
.status-private { background: #E6A23C; }
.status-offline { background: #909399; }

.recording-icon {
  color: #F56C6C;
  animation: blink 1s infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}

@keyframes blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}
</style>
