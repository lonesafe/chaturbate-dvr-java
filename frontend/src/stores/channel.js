import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { channelApi, recordingApi } from '../api'

export const useChannelStore = defineStore('channel', () => {
  // State
  const channels = ref([])
  const recordings = ref([])
  const loading = ref(false)

  // Getters
  const enabledChannels = computed(() => channels.value.filter(c => c.enabled))
  const recordingChannels = computed(() => channels.value.filter(c => c.recording))
  const onlineChannels = computed(() => channels.value.filter(c => c.lastStatus === 'public'))
  
  const stats = computed(() => ({
    total: channels.value.length,
    online: onlineChannels.value.length,
    recording: recordingChannels.value.length,
    offline: channels.value.filter(c => c.lastStatus === 'offline').length,
    private: channels.value.filter(c => c.lastStatus === 'private').length
  }))

  // Actions
  const fetchChannels = async () => {
    try {
      const data = await channelApi.getAll()
      channels.value = data
    } catch (e) {
      console.error('获取直播间失败:', e)
    }
  }

  const fetchRecordings = async () => {
    try {
      const data = await recordingApi.getAll()
      recordings.value = data
    } catch (e) {
      console.error('获取录制记录失败:', e)
    }
  }

  const addChannel = async (channelData) => {
    await channelApi.add(channelData)
    await fetchChannels()
  }

  const deleteChannel = async (id) => {
    await channelApi.delete(id)
    await fetchChannels()
  }

  const toggleChannel = async (id) => {
    await channelApi.toggle(id)
    await fetchChannels()
  }

  return {
    channels,
    recordings,
    loading,
    enabledChannels,
    recordingChannels,
    onlineChannels,
    stats,
    fetchChannels,
    fetchRecordings,
    addChannel,
    deleteChannel,
    toggleChannel
  }
})
