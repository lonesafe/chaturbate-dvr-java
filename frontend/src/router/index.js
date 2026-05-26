import { createRouter, createWebHashHistory } from 'vue-router'
import HomeView from '../views/HomeView.vue'
import ChannelsView from '../views/ChannelsView.vue'
import RecordingsView from '../views/RecordingsView.vue'
import SettingsView from '../views/SettingsView.vue'

const routes = [
  {
    path: '/',
    name: 'home',
    component: HomeView
  },
  {
    path: '/channels',
    name: 'channels',
    component: ChannelsView
  },
  {
    path: '/recordings',
    name: 'recordings',
    component: RecordingsView
  },
  {
    path: '/settings',
    name: 'settings',
    component: SettingsView
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

export default router
