<template>
  <div class="channels-page">
    <el-card>
      <template #header>
        <div class="page-header">
          <span>📋 直播间管理</span>
          <el-button type="primary" @click="showAddDialog = true">
            <el-icon><Plus /></el-icon> 添加直播间
          </el-button>
          <el-button @click="showBatchDialog = true">
            <el-icon><Plus /></el-icon> 批量添加
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
        <el-table-column label="频道" min-width="200">
          <template #default="{ row }">
            <div class="channel-name">
              <el-avatar :size="32" :style="{ background: getAvatarColor(row.username) }">
                {{ row.username.charAt(0).toUpperCase() }}
              </el-avatar>
              <div class="channel-info">
                <div class="username">
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
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <span class="status-badge" :class="getStatusClass(channelStatuses[row.username])">
              {{ getStatusText(channelStatuses[row.username]) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="录制状态" width="100">
          <template #default="{ row }">
            <el-tag v-if="recordingUsernames.includes(row.username)" type="danger" size="small">
              录制中
            </el-tag>
            <el-tag v-else type="info" size="small">
              未录制
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
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <el-button 
              v-if="recordingUsernames.includes(row.username)"
              type="danger" 
              size="small"
              @click="handleStopRecording(row)"
            >
              停止录制
            </el-button>
            <el-button 
              v-else-if="channelStatuses[row.username] === 'public'"
              type="primary" 
              size="small"
              @click="handleStartRecording(row)"
            >
              开始录制
            </el-button>
            <el-button size="small" @click="handleViewLogs(row)">日志</el-button>
            <el-button 
              v-if="row.recording"
              type="warning" 
              size="small"
              @click="handleViewDownloads(row)"
            >
              下载监控
            </el-button>
            <el-button size="small" @click="editChannel(row)">编辑</el-button>
            <el-button type="danger" size="small" @click="handleDelete(row)">删除</el-button>
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

    <!-- 批量添加直播间对话框 -->
    <el-dialog v-model="showBatchDialog" title="批量添加直播间" width="500px">
      <el-form label-width="80px">
        <el-form-item label="用户名列表" required>
          <el-input
            v-model="batchInput"
            type="textarea"
            :rows="10"
            placeholder="输入用户名，每行一个或用逗号分隔&#10;例如：&#10;user1&#10;user2&#10;user3"
          />
        </el-form-item>
        <el-form-item>
          <span style="color: #909399; font-size: 12px;">已存在的用户名会自动跳过</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showBatchDialog = false">取消</el-button>
        <el-button type="primary" @click="handleBatchAdd" :loading="batchAdding">添加</el-button>
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

    <!-- 日志对话框 -->
    <el-dialog v-model="showLogsDialog" title="录制日志" width="700px">
      <div class="logs-container">
        <div v-if="currentLogs.length === 0" class="no-logs">暂无日志</div>
        <pre v-else class="logs-content"><code>{{ currentLogs.join('\n') }}</code></pre>
      </div>
      <template #footer>
        <el-button @click="showLogsDialog = false">关闭</el-button>
        <el-button @click="refreshLogs" :loading="logsLoading">刷新</el-button>
      </template>
    </el-dialog>

    <!-- 下载监控对话框 -->
    <el-dialog v-model="showDownloadsDialog" :title="'下载监控 - ' + currentDownloadUsername" width="800px">
      <div class="download-monitor">
        <el-alert
          v-if="currentDownloads.length === 0"
          title="当前没有活跃的下载任务"
          type="info"
          :closable="false"
          show-icon
        />
        <el-table v-else :data="currentDownloads" style="width: 100%">
          <el-table-column prop="type" label="类型" width="80">
            <template #default="{ row }">
              <el-tag :type="row.type === 'video' ? 'primary' : 'success'" size="small">
                {{ row.type === 'video' ? '视频' : '音频' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="filename" label="文件名" min-width="150" show-overflow-tooltip />
          <el-table-column prop="url" label="下载地址" min-width="300" show-overflow-tooltip>
            <template #default="{ row }">
              <a :href="row.url" target="_blank" class="download-link">{{ row.url }}</a>
            </template>
          </el-table-column>
          <el-table-column prop="elapsedSeconds" label="已耗时" width="100">
            <template #default="{ row }">
              <span :class="{ 'download-stuck': row.elapsedSeconds > 30 }">
                {{ formatDuration(row.elapsedSeconds) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <el-tag v-if="row.elapsedSeconds > 30" type="danger" size="small">卡住?</el-tag>
              <el-tag v-else type="success" size="small">下载中</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <template #footer>
        <el-button @click="showDownloadsDialog = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { useChannelStore } from '../stores/channel'
import { channelApi } from '../api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Search } from '@element-plus/icons-vue'

const store = useChannelStore()
const loading = ref(false)
const filter = ref('all')
const searchText = ref('')

// 录制状态（从内存获取，非数据库）
const recordingUsernames = ref([])

// 频道状态映射（从ChannelMonitorTask获取）
const channelStatuses = ref({})

// 状态轮询定时器
const statusesTimer = ref(null)

// 下载信息（实时更新）
const downloadInfo = ref({})
const downloadInfoTimer = ref(null)

// 日志相关
const showLogsDialog = ref(false)
const currentLogs = ref([])
const logsLoading = ref(false)
const currentLogUsername = ref('')

// 下载监控相关
const showDownloadsDialog = ref(false)
const downloadsRefreshTimer = ref(null)
const currentDownloadUsername = ref('')

// 直接从 downloadInfo 获取当前活跃下载（响应式）
const currentDownloads = computed(() => {
  if (!currentDownloadUsername.value) return []
  const info = downloadInfo.value[currentDownloadUsername.value]
  return info?.activeDownloads || []
})

// 监听下载监控对话框关闭
watch(showDownloadsDialog, (newVal) => {
  if (!newVal) {
    stopDownloadsRefresh()
    currentDownloadUsername.value = ''
  }
})

// 添加对话框
const showAddDialog = ref(false)
const newChannel = ref({ username: '', displayName: '' })

// 批量添加对话框
const showBatchDialog = ref(false)
const batchInput = ref('')
const batchAdding = ref(false)
const adding = ref(false)

// 编辑对话框
const showEditDialog = ref(false)
const editing = ref(false)
const editForm = ref({ id: null, username: '', displayName: '' })

const filteredChannels = computed(() => {
  let list = store.channels
  
  // 筛选（使用内存中的状态）
  if (filter.value === 'online') {
    list = list.filter(c => channelStatuses.value[c.username] === 'public')
  } else if (filter.value === 'recording') {
    list = list.filter(c => recordingUsernames.value.includes(c.username))
  } else if (filter.value === 'offline') {
    list = list.filter(c => channelStatuses.value[c.username] === 'offline')
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

const getStatusClass = (status) => {
  if (status === 'public') return 'status-online'
  if (status === 'private') return 'status-private'
  return 'status-offline'
}

const getStatusText = (status) => {
  if (status === 'public') return '在线'
  if (status === 'private') return '私密'
  return '离线'
}

const formatDuration = (seconds) => {
  if (!seconds && seconds !== 0) return '-'
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  const secs = seconds % 60
  if (hours > 0) {
    return `${hours}小时${minutes}分${secs}秒`
  }
  return `${minutes}分${secs}秒`
}

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

const handleBatchAdd = async () => {
  if (!batchInput.value.trim()) {
    ElMessage.warning('请输入用户名')
    return
  }
  batchAdding.value = true
  try {
    const res = await channelApi.batchAdd(batchInput.value)
    showBatchDialog.value = false
    batchInput.value = ''
    await store.fetchChannels()
    if (res.skipped > 0 || res.failed > 0) {
      ElMessage.warning(`添加完成：新增 ${res.added} 个，跳过 ${res.skipped} 个（已存在），失败 ${res.failed} 个`)
    } else {
      ElMessage.success(`成功添加 ${res.added} 个直播间`)
    }
  } catch (e) {
    ElMessage.error(e || '批量添加失败')
  } finally {
    batchAdding.value = false
  }
}

const editChannel = (channel) => {
  editForm.value = { ...channel }
  showEditDialog.value = true
}

const handleEdit = async () => {
  editing.value = true
  try {
    await channelApi.update(editForm.value.id, {
      displayName: editForm.value.displayName
    })
    ElMessage.success('保存成功')
    showEditDialog.value = false
    await store.fetchChannels()
  } catch (e) {
    ElMessage.error('保存失败')
  } finally {
    editing.value = false
  }
}

const handleDelete = async (channel) => {
  try {
    await ElMessageBox.confirm(
      `确定删除直播间 "${channel.username}" 吗？${recordingUsernames.value.includes(channel.username) ? '（当前正在录制，将先停止）' : ''}`,
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

const handleStartRecording = async (channel) => {
  try {
    await store.startRecording(channel.username)
    ElMessage.success(`开始录制 ${channel.username}`)
    // 启动下载信息轮询
    startDownloadInfoPolling()
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '开始录制失败')
  }
}

const handleStopRecording = async (channel) => {
  try {
    await ElMessageBox.confirm(
      `确定停止录制 "${channel.username}" 吗？停止后会立即合并未合并的分片。`,
      '确认停止',
      { type: 'warning' }
    )
    await store.stopRecording(channel.username)
    ElMessage.success(`停止录制 ${channel.username}`)
    // 移除下载信息
    delete downloadInfo.value[channel.username]
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e.response?.data?.message || '停止录制失败')
  }
}

const handleViewLogs = async (channel) => {
  currentLogUsername.value = channel.username
  showLogsDialog.value = true
  await refreshLogs()
}

const refreshLogs = async () => {
  if (!currentLogUsername.value) return
  logsLoading.value = true
  try {
    const res = await channelApi.getLogs(currentLogUsername.value)
    currentLogs.value = res.logs || []
  } catch (e) {
    ElMessage.error('获取日志失败')
  } finally {
    logsLoading.value = false
  }
}

const handleViewDownloads = (channel) => {
  currentDownloadUsername.value = channel.username
  showDownloadsDialog.value = true
  // 立即刷新一次
  fetchDownloadInfo()
  // 启动实时刷新
  startDownloadsRefresh()
}

const startDownloadsRefresh = () => {
  if (downloadsRefreshTimer.value) return
  downloadsRefreshTimer.value = setInterval(() => {
    if (showDownloadsDialog.value) {
      fetchDownloadInfo()
    } else {
      // 对话框已关闭，停止刷新
      clearInterval(downloadsRefreshTimer.value)
      downloadsRefreshTimer.value = null
    }
  }, 1500)
}

const stopDownloadsRefresh = () => {
  if (downloadsRefreshTimer.value) {
    clearInterval(downloadsRefreshTimer.value)
    downloadsRefreshTimer.value = null
  }
}

// 获取所有正在录制的频道的下载信息
const fetchDownloadInfo = async () => {
  if (recordingUsernames.value.length === 0) {
    // 没有正在录制的频道，停止轮询
    if (downloadInfoTimer.value) {
      clearInterval(downloadInfoTimer.value)
      downloadInfoTimer.value = null
    }
    return
  }

  for (const username of recordingUsernames.value) {
    try {
      const res = await channelApi.getDownloadInfo(username)
      downloadInfo.value[username] = res
    } catch (e) {
      console.error(`获取 ${username} 下载信息失败:`, e)
    }
  }
}

const startDownloadInfoPolling = () => {
  // 如果已经在轮询，不再启动
  if (downloadInfoTimer.value) return
  // 立即获取一次
  fetchDownloadInfo()
  // 每2秒轮询一次
  downloadInfoTimer.value = setInterval(fetchDownloadInfo, 2000)
}

const stopDownloadInfoPolling = () => {
  if (downloadInfoTimer.value) {
    clearInterval(downloadInfoTimer.value)
    downloadInfoTimer.value = null
  }
}

// 获取录制列表
const fetchRecordingList = async () => {
  try {
    const res = await channelApi.getRecording()
    recordingUsernames.value = res.map(c => c.username)
  } catch (e) {
    console.error('获取录制列表失败:', e)
  }
}

// 获取频道状态
const fetchChannelStatuses = async () => {
  try {
    const res = await channelApi.getStatuses()
    channelStatuses.value = res || {}
  } catch (e) {
    console.error('获取频道状态失败:', e)
  }
}

// 启动状态轮询
const startStatusesPolling = () => {
  if (statusesTimer.value) return
  // 立即获取一次
  fetchRecordingList()
  fetchChannelStatuses()
  // 每10秒轮询一次
  statusesTimer.value = setInterval(() => {
    fetchRecordingList()
    fetchChannelStatuses()
  }, 10000)
}

const stopStatusesPolling = () => {
  if (statusesTimer.value) {
    clearInterval(statusesTimer.value)
    statusesTimer.value = null
  }
}

// 生命周期
onMounted(async () => {
  loading.value = true
  try {
    await store.fetchChannels()
    // 启动状态轮询（包含录制列表和频道状态）
    startStatusesPolling()
  } finally {
    loading.value = false
  }
})

onUnmounted(() => {
  stopDownloadInfoPolling()
  stopStatusesPolling()
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

.status-badge {
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 12px;
}

.status-online {
  background: #f0f9eb;
  color: #67C23A;
}

.status-private {
  background: #fdf6ec;
  color: #E6A23C;
}

.status-offline {
  background: #f4f4f5;
  color: #909399;
}

.logs-container {
  max-height: 500px;
  overflow-y: auto;
}

.logs-content {
  background: #1e1e1e;
  color: #d4d4d4;
  padding: 12px;
  border-radius: 4px;
  font-size: 12px;
  line-height: 1.5;
  max-height: 450px;
  overflow-y: auto;
  white-space: pre-wrap;
  word-break: break-all;
}

.no-logs {
  text-align: center;
  color: #909399;
  padding: 40px;
}

.download-monitor {
  max-height: 500px;
}

.download-link {
  color: #409EFF;
  text-decoration: none;
  font-size: 12px;
}

.download-link:hover {
  text-decoration: underline;
}

.download-stuck {
  color: #F56C6C;
  font-weight: bold;
}
</style>
