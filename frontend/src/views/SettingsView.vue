<template>
  <div class="settings-page">
    <el-card>
      <template #header>
        <span>⚙️ 系统设置</span>
      </template>

      <el-tabs v-model="activeTab" class="settings-tabs">
        <!-- 基本设置 -->
        <el-tab-pane label="基本设置" name="basic">
          <el-form :model="basicSettings" label-width="150px" class="settings-form">
            <el-form-item label="Cookie">
              <el-input 
                v-model="basicSettings.cookie" 
                type="textarea" 
                :rows="3"
                placeholder="输入 cf_clearance cookie"
              />
              <div class="form-tip">Cloudflare cf_clearance cookie，用于 API 访问</div>
            </el-form-item>

            <el-form-item label="User-Agent">
              <el-input 
                v-model="basicSettings.user_agent" 
                type="textarea" 
                :rows="2"
                placeholder="浏览器 User-Agent"
              />
              <div class="form-tip">浏览器 User-Agent，模拟真实浏览器访问</div>
            </el-form-item>

            <el-form-item label="API 基础 URL">
              <el-input 
                v-model="basicSettings.api_base_url" 
                placeholder="https://zh-hans.chaturbate.com/api/chatvideocontext/"
              />
              <div class="form-tip">Chaturbate API 基础 URL</div>
            </el-form-item>

            <el-form-item>
              <el-button type="primary" @click="saveBasicSettings" :loading="saving">
                保存基本设置
              </el-button>
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <!-- 录制设置 -->
        <el-tab-pane label="录制设置" name="recording">
          <el-form :model="recordingSettings" label-width="150px" class="settings-form">
            <el-form-item label="录制路径">
              <el-input 
                v-model="recordingSettings.record_path" 
                placeholder="./recordings/{username}/{username}-{yyyy-mm-dd}.mp4"
              />
              <div class="form-tip">支持占位符：{username}（用户名）、{yyyy-mm-dd}（日期）<br/>例：./recordings/{username}/{username}-{yyyy-mm-dd}.mp4</div>
            </el-form-item>

            <el-form-item label="临时文件路径">
              <el-input 
                v-model="recordingSettings.tmp_path" 
                placeholder="./tmp"
              />
              <div class="form-tip">临时文件目录（下载片段、合并 part 文件）</div>
            </el-form-item>

            <el-form-item label="首选质量">
              <el-select v-model="recordingSettings.preferred_quality" placeholder="选择质量">
                <el-option label="360p" value="360p" />
                <el-option label="480p" value="480p" />
                <el-option label="540p" value="540p" />
                <el-option label="720p" value="720p" />
                <el-option label="1080p" value="1080p" />
              </el-select>
              <div class="form-tip">优先录制的分辨率，如果不可用则自动选择下一个</div>
            </el-form-item>

            <el-form-item label="HLS 片段时长">
              <el-input-number 
                v-model="recordingSettings.segment_duration_seconds" 
                :min="5" 
                :max="30" 
              />
              <div class="form-tip">HLS 片段时长（秒），影响合并频率</div>
            </el-form-item>

            <el-form-item>
              <el-button type="primary" @click="saveRecordingSettings" :loading="saving">
                保存录制设置
              </el-button>
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <!-- 高级设置 -->
        <el-tab-pane label="高级设置" name="advanced">
          <el-form :model="advancedSettings" label-width="150px" class="settings-form">
            <el-form-item label="检查间隔">
              <el-input-number 
                v-model="advancedSettings.check_interval_seconds" 
                :min="10" 
                :max="300" 
              />
              <div class="form-tip">直播间状态检查间隔（秒）</div>
            </el-form-item>

            <el-form-item label="下载线程数">
              <el-input-number 
                v-model="advancedSettings.download_threads" 
                :min="1" 
                :max="16" 
              />
              <div class="form-tip">并发下载片段的线程数</div>
            </el-form-item>

            <el-form-item label="ffmpeg 路径">
              <el-input 
                v-model="advancedSettings.ffmpeg_path" 
                placeholder="ffmpeg"
              />
              <div class="form-tip">ffmpeg 可执行文件路径（如果在 PATH 中可直接写 ffmpeg）</div>
            </el-form-item>

            <el-form-item>
              <el-button type="primary" @click="saveAdvancedSettings" :loading="saving">
                保存高级设置
              </el-button>
            </el-form-item>
          </el-form>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { configApi } from '../api'
import { ElMessage } from 'element-plus'

const activeTab = ref('basic')
const saving = ref(false)

// 基本设置
const basicSettings = ref({
  cookie: '',
  user_agent: '',
  api_base_url: ''
})

// 录制设置
const recordingSettings = ref({
  record_path: '',
  tmp_path: '',
  preferred_quality: '720p',
  segment_duration_seconds: 10
})

// 高级设置
const advancedSettings = ref({
  check_interval_seconds: 30,
  download_threads: 4,
  ffmpeg_path: 'ffmpeg'
})

// 加载所有配置
const loadConfigs = async () => {
  try {
    const res = await configApi.getAll()
    if (res.success) {
      const configs = res.data
      // 解析配置到表单
      configs.forEach(config => {
        const key = config.configKey
        const value = config.configValue
        
        // 基本设置
        if (key === 'cookie') basicSettings.value.cookie = value
        if (key === 'user_agent') basicSettings.value.user_agent = value
        if (key === 'api_base_url') basicSettings.value.api_base_url = value
        
        // 录制设置
        if (key === 'record_path') recordingSettings.value.record_path = value
        if (key === 'tmp_path') recordingSettings.value.tmp_path = value
        if (key === 'preferred_quality') recordingSettings.value.preferred_quality = value
        if (key === 'segment_duration_seconds') recordingSettings.value.segment_duration_seconds = parseInt(value)
        
        // 高级设置
        if (key === 'check_interval_seconds') advancedSettings.value.check_interval_seconds = parseInt(value)
        if (key === 'download_threads') advancedSettings.value.download_threads = parseInt(value)
        if (key === 'ffmpeg_path') advancedSettings.value.ffmpeg_path = value
      })
    }
  } catch (e) {
    ElMessage.error('加载配置失败: ' + (e || '未知错误'))
  }
}

// 保存基本设置
const saveBasicSettings = async () => {
  saving.value = true
  try {
    const configs = {
      'cookie': basicSettings.value.cookie,
      'user_agent': basicSettings.value.user_agent,
      'api_base_url': basicSettings.value.api_base_url
    }
    await configApi.batchUpdate(configs)
    ElMessage.success('基本设置保存成功')
  } catch (e) {
    ElMessage.error('保存失败: ' + (e || '未知错误'))
  } finally {
    saving.value = false
  }
}

// 保存录制设置
const saveRecordingSettings = async () => {
  saving.value = true
  try {
    const configs = {
      'record_path': recordingSettings.value.record_path,
      'tmp_path': recordingSettings.value.tmp_path,
      'preferred_quality': recordingSettings.value.preferred_quality,
      'segment_duration_seconds': recordingSettings.value.segment_duration_seconds.toString()
    }
    await configApi.batchUpdate(configs)
    ElMessage.success('录制设置保存成功')
  } catch (e) {
    ElMessage.error('保存失败: ' + (e || '未知错误'))
  } finally {
    saving.value = false
  }
}

// 保存高级设置
const saveAdvancedSettings = async () => {
  saving.value = true
  try {
    const configs = {
      'check_interval_seconds': advancedSettings.value.check_interval_seconds.toString(),
      'download_threads': advancedSettings.value.download_threads.toString(),
      'ffmpeg_path': advancedSettings.value.ffmpeg_path
    }
    await configApi.batchUpdate(configs)
    ElMessage.success('高级设置保存成功')
  } catch (e) {
    ElMessage.error('保存失败: ' + (e || '未知错误'))
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  loadConfigs()
})
</script>

<style scoped>
.settings-page {
  max-width: 900px;
  margin: 0 auto;
}

.settings-tabs {
  margin-top: 20px;
}

.settings-form {
  max-width: 600px;
  margin-top: 20px;
}

.form-tip {
  font-size: 12px;
  color: #909399;
  margin-top: 5px;
}

.el-form-item {
  margin-bottom: 25px;
}
</style>
