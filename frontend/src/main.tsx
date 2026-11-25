import { createRoot } from 'react-dom/client'
import './style.css'
import { Chat } from './chat'

const ROOT_ID = 'root'

// 将单用户对话页面挂载到现有入口。
createRoot(document.getElementById(ROOT_ID)!).render(<Chat />)
