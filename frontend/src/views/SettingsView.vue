<template>
  <div class="settings-page">
    <el-row :gutter="20">
      <!-- 基本设置 -->
      <el-col :span="12">
        <el-card>
          <template #header>
            <div class="card-header">
              <el-icon><Setting /></el-icon>
              <span>基本设置</span>
            </div>
          </template>
          
          <el-form :model="settings" label-width="120px">
            <el-form-item label="Cookie">
              <el-input
                v-model="settings.cookie"
                type="textarea"
                :rows="3"
                placeholder="cf_clearance=..."
              />
              <div class="form-tip">用于访问API的Cloudflare验证Cookie</div>
            </el-form-item>
            
            <el-form-item label="User-Agent">
              <el-input
                v-model="settings.userAgent"
                placeholder="Mozilla/5.0 ..."
              />
            </el-form-item>
            
            <el-form-item label="录制路径">
              <el-input
                v-model="settings.recordPath"
                placeholder="./recordings"
              />
              <div class="form-tip">录制文件保存的目录路径</div>
            </el-form-item>
            
            <el-form-item label="检查间隔">
              <el-input-number
                v-model="settings.checkInterval"
                :min="10"
                :max="300"
                :step="10"
              />
              <span class="unit">秒</span>
              <div class="form-tip">检查直播间状态的时间间隔</div>
            </el-form-item>
            
            <el-form-item label="优先质量">
              <el-select v-model="settings.preferredQuality" style="width: 120px">
                <el-option label="360p" value="360p" />
                <el-option label="480p" value="480p" />
                <el-option label="540p" value="540p" />
                <el-option label="720p" value="720p" />
                <el-option label="1080p" value="1080p" />
              </el-select>
            </el-form-item>
            
            <el-form-item>
              <el-button type="primary" @click="saveSettings" :loading="saving">
                保存设置
              </el-button>
              <el-button @click="resetSettings">重置</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>
      
      <!-- 系统信息 -->
      <el-col :span="12">
        <el-card>
          <template #header>
            <div class="card-header">
              <el-icon><InfoFilled /></el-icon>
              <span>系统信息</span>
            </div>
          </template>
          
          <div class="info-list">
            <div class="info-item">
              <span class="info-label">系统状态</span>
              <el-tag type="success" effect="dark">运行中</el-tag>
            </div>
            <div class="info-item">
              <span class="info-label">监控直播间</span>
              <span class="info-value">{{ store.stats.total }} 个</span>
            </div>
            <div class="info-item">
              <span class="info-label">正在录制</span>
              <span class="info-value">{{ store.stats.recording }} 个</span>
            </div>
            <div class="info-item">
              <span class="info-label">录制记录</span>
              <span class="info-value">{{ store.recordings.length }} 条</span>
            </div>
          </div>
        </el-card>
        
        <el-card style="margin-top: 20px;">
          <template #header>
            <div class="card-header">
              <el-icon><Warning /></el-icon>
              <span>注意事项</span>
            </div>
          </template>
          
          <div class="notice-list">
            <div class="notice-item">
              <el-icon color="#E6A23C"><Warning /></el-icon>
              <span>Cookie 会定期过期，需要手动更新</span>
            </div>
            <div class="notice-item">
              <el-icon color="#409EFF"><InfoFilled /></el-icon>
              <span>录制文件保存在本地，注意磁盘空间</span>
            </div>
            <div class="notice-item">
              <el-icon color="#67C23A"><SuccessFilled /></el-icon>
              <span>建议定期检查录制质量设置</span>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useChannelStore } from '../stores/channel'
import { ElMessage } from 'element-plus'

const store = useChannelStore()
const saving = ref(false)

const settings = ref({
  cookie: '',
  userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36',
  recordPath: './recordings',
  checkInterval: 30,
  preferredQuality: '720p'
})

const saveSettings = async () => {
  saving.value = true
  try {
    // TODO: 调用保存设置API
    ElMessage.success('设置已保存')
  } catch (e) {
    ElMessage.error('保存失败')
  } finally {
    saving.value = false
  }
}

const resetSettings = () => {
  settings.value = {
    cookie: '',
    userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36',
    recordPath: './recordings',
    checkInterval: 30,
    preferredQuality: '720p'
  }
  ElMessage.info('已重置为默认设置')
}
</script>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  gap: 8px;
}

.form-tip {
  font-size: 12px;
  color: #909399;
  margin-top: 5px;
}

.unit {
  margin-left: 10px;
  color: #606266;
}

.info-list {
  display: flex;
  flex-direction: column;
  gap: 15px;
}

.info-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 0;
  border-bottom: 1px solid #ebeef5;
}

.info-item:last-child {
  border-bottom: none;
}

.info-label {
  color: #606266;
}

.info-value {
  font-weight: bold;
  color: #303133;
}

.notice-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.notice-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px;
  background: #f5f7fa;
  border-radius: 4px;
}
</style>
