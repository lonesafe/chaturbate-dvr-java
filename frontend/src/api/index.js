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

// 录制记录API
export const recordingApi = {
  getAll: () => api.get('/recordings'),
  getByChannel: (channelId) => api.get(`/recordings/channel/${channelId}`),
  getActive: () => api.get('/recordings/active'),
  getById: (id) => api.get(`/recordings/${id}`),
  delete: (id) => api.delete(`/recordings/${id}`)
}

export default api
