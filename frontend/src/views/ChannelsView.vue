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
                <div class="username">{{ row.username }}</div>
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
import { ref, computed } from 'vue'
import { useChannelStore } from '../stores/channel'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'

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

const startRecording = (channel) => {
  ElMessage.info(`开始录制 ${channel.username}`)
  // TODO: 调用开始录制API
}

const stopRecording = (channel) => {
  ElMessage.info(`停止录制 ${channel.username}`)
  // TODO: 调用停止录制API
}
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
