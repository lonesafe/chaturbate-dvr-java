import axios from 'axios'

const api = axios.create({
  baseURL: '/api',
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json'
  }
})

// 请求拦截器
api.interceptors.request.use(
  config => {
    return config
  },
  error => {
    return Promise.reject(error)
  }
)

// 响应拦截器
api.interceptors.response.use(
  response => {
    return response.data
  },
  error => {
    const message = error.response?.data || error.message || '请求失败'
    return Promise.reject(message)
  }
)

// 直播间API
export const channelApi = {
  getAll: () => api.get('/channels'),
  getEnabled: () => api.get('/channels/enabled'),
  getRecording: () => api.get('/channels/recording'),
  add: (data) => api.post('/channels', data),
  update: (id, data) => api.put(`/channels/${id}`, data),
  delete: (id) => api.delete(`/channels/${id}`),
  toggle: (id) => api.put(`/channels/${id}/toggle`)
}

// 录制API
export const recordingApi = {
  // 获取所有录制记录
  getAll: () => api.get('/recordings'),
  // 获取指定频道的录制记录
  getByChannel: (channelId) => api.get(`/recordings/channel/${channelId}`),
  // 获取正在进行的录制
  getActive: () => api.get('/recordings/active'),
  // 获取单个录制详情
  getById: (id) => api.get(`/recordings/${id}`),
  // 删除录制记录
  delete: (id) => api.delete(`/recordings/${id}`),
  // 开始录制
  start: (username) => api.post(`/recordings/start?username=${username}`),
  // 停止录制
  stop: (username) => api.post(`/recordings/stop?username=${username}`),
  // 获取录制日志
  getLogs: (username) => api.get(`/recordings/logs/${username}`)
}

// 配置API
export const configApi = {
  // 获取所有配置
  getAll: () => api.get('/config'),
  // 根据键获取配置
  get: (key) => api.get(`/config/${key}`),
  // 更新单个配置
  update: (key, value) => api.put(`/config/${key}`, { value }),
  // 批量更新配置
  batchUpdate: (configs) => api.put('/config/batch', configs),
  // 删除配置
  delete: (key) => api.delete(`/config/${key}`)
}

export default api
