# 💻 Ticket FDS Frontend (`ticket-fds-frontend`)

> **암표 의심 계정 모니터링 & 대시보드 웹 애플리케이션**
> FDS 백엔드에서 집계된 이상 사용자 탐지 현황, 실시간 이벤트 로그 및 관리자 조치 인터페이스를 제공하는 프론트엔드 레포지토리입니다.

---

## 🛠 Tech Stack

* **Framework**: React / Next.js (또는 팀 스택에 맞춰 선택)
* **Language**: TypeScript / JavaScript
* **Package Manager**: npm / pnpm / yarn
* **Styling**: Tailwind CSS

---

## 🚀 Quick Start (Local Setup)

### 1. 의존성 설치
```bash
npm install
```

### 2. 개발 서버 실행
```bash
npm run dev
```
- 로컬 접속 주소: http://localhost:5173

## 🔗 Environment Variables (`.env.local`)

코드 스니펫
```bash
NEXT_PUBLIC_API_BASE_URL=http://localhost:8080/api/v1
```

## 🌿 Git 브랜치 전략

- `main`: 운영 배포 브랜치
- `dev`: 개발 브랜치
- `feat/{issue_no}-{기능명}`: 화면/컴포넌트 단위 개발 브랜치
