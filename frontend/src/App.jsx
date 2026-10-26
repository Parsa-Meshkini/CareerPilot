import { NavLink, Navigate, Route, Routes } from "react-router-dom";
import { useAuth } from "./auth";
import JobDetail from "./pages/JobDetail";
import Jobs from "./pages/Jobs";
import Login from "./pages/Login";
import Profile from "./pages/Profile";

const NAV = [["/jobs", "Jobs"], ["/profile", "Profile"]];

function Layout({ children }) {
  const { user, signOut } = useAuth();
  return (
    <div className="app">
      <aside className="sidebar">
        <div className="brand">Career<span>Pilot</span></div>
        <nav>{NAV.map(([to, label]) => <NavLink key={to} to={to}>{label}</NavLink>)}</nav>
        <div className="sidebar-foot">
          <div className="small muted">{user.email}</div>
          <button className="link" onClick={signOut}>Sign out</button>
          <p className="tiny muted">CareerPilot never submits applications. You do.</p>
        </div>
      </aside>
      <main className="content">{children}</main>
    </div>
  );
}

export default function App() {
  const { user, ready } = useAuth();
  if (!ready) return null;
  if (!user) return <Login />;
  return (
    <Layout>
      <Routes>
        <Route path="/jobs" element={<Jobs />} />
        <Route path="/jobs/:id" element={<JobDetail />} />
        <Route path="/profile" element={<Profile />} />
        <Route path="*" element={<Navigate to="/profile" replace />} />
      </Routes>
    </Layout>
  );
}
