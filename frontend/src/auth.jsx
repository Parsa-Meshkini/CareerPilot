import { createContext, useContext, useEffect, useState } from "react";
import { get, getToken, post, setToken } from "./api";

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    const onLogout = () => setUser(null);
    window.addEventListener("careerpilot:logout", onLogout);
    if (getToken()) get("/auth/me/").then(setUser).catch(() => setToken(null)).finally(() => setReady(true));
    else setReady(true);
    return () => window.removeEventListener("careerpilot:logout", onLogout);
  }, []);

  const signIn = async (mode, email, password, display_name) => {
    const data = await post(`/auth/${mode}/`, { email, password, display_name });
    setToken(data.token);
    setUser(data.user);
  };
  const signOut = () => { setToken(null); setUser(null); };

  return <AuthContext.Provider value={{ user, ready, signIn, signOut }}>{children}</AuthContext.Provider>;
}

export const useAuth = () => useContext(AuthContext);
