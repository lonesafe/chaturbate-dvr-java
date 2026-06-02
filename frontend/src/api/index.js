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
  getStatuses: () => api.get('/channels/statuses'),
  add: (data) => api.post('/channels', data),
  update: (id, data) => api.put(`/channels/${id}`, data),
  delete: (id) => api.delete(`/channels/${id}`),
  toggle: (id) => api.put(`/channels/${id}/toggle`),
  startRecording: (username) => api.post(`/channels/${username}/start`),
  stopRecording: (username) => api.post(`/channels/${username}/stop`),
  getDownloadInfo: (username) => api.get(`/channels/${username}/download-info`),
  getLogs: (username) => api.get(`/channels/${username}/logs`)
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
