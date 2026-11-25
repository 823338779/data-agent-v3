import { createRoot } from 'react-dom/client'
import './style.css'

const ROOT_ID = 'root'
const HEALTH_PATH = '/actuator/health'

// 将应用首页挂载到宿主页面，并提供后端健康检查入口。
createRoot(document.getElementById(ROOT_ID)!).render(
  <main>
    <h1>Data Agent V3</h1>
    <a href={HEALTH_PATH}>查看后端健康状态</a>
  </main>,
)
