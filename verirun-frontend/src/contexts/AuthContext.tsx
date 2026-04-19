import React, { createContext, useContext, useState, useEffect } from 'react';
import type { ReactNode } from 'react';

interface User {
  email: string;
}

interface AuthResult {
  success: boolean;
  errorMessage?: string;
}

interface AuthContextType {
  currentUser: User | null;
  isAuthenticated: boolean;
  login: (email: string, password: string) => Promise<AuthResult>;
  logout: () => Promise<void>;
  register: (email: string, password: string) => Promise<AuthResult>;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

interface AuthProviderProps {
  children: ReactNode;
}

export const AuthProvider: React.FC<AuthProviderProps> = ({ children }) => {
  const [currentUser, setCurrentUser] = useState<User | null>(null);
  const [isAuthenticated, setIsAuthenticated] = useState(false);

  useEffect(() => {
    const checkAuthStatus = async () => {
      try {
        const response = await fetch('/api/auth/status', {
          method: 'GET',
          credentials: 'include'
        });

        if (response.ok) {
          const authStatus = await response.json();
          if (authStatus.authenticated && authStatus.email) {
            setCurrentUser({ email: authStatus.email });
            setIsAuthenticated(true);
          }
        }
      } catch {
        console.warn('Auth status check failed');
      }
    };

    checkAuthStatus();
  }, []);

  const login = async (email: string, password: string): Promise<AuthResult> => {
    try {
      const response = await fetch('/api/auth/login', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ email, password }),
        credentials: 'include'
      });

      if (response.ok) {
        setCurrentUser({ email });
        setIsAuthenticated(true);
        return { success: true };
      }

      if (response.status === 401) {
        return { success: false, errorMessage: 'Invalid email or password' };
      }

      if (response.status === 429) {
        return { success: false, errorMessage: 'Too many attempts. Please wait a moment and try again.' };
      }

      if (response.status >= 500) {
        return { success: false, errorMessage: 'Server error. Please try again later.' };
      }

      return { success: false, errorMessage: 'Login failed. Please try again.' };
    } catch {
      return { success: false, errorMessage: 'Cannot connect to server. Check your internet connection.' };
    }
  };

  const logout = async (): Promise<void> => {
    try {
      await fetch('/api/auth/logout', {
        method: 'POST',
        credentials: 'include'
      });
    } catch {
      console.warn('Logout request failed');
    } finally {
      setCurrentUser(null);
      setIsAuthenticated(false);
    }
  };

  const register = async (email: string, password: string): Promise<AuthResult> => {
    try {
      const response = await fetch('/api/auth/register', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ email, password }),
        credentials: 'include'
      });

      if (response.ok) {
        return { success: true };
      }

      if (response.status === 409) {
        return { success: false, errorMessage: 'An account with this email already exists.' };
      }

      if (response.status === 400) {
        return { success: false, errorMessage: 'Invalid input. Please check your email and password.' };
      }

      if (response.status === 429) {
        return { success: false, errorMessage: 'Too many attempts. Please wait a moment and try again.' };
      }

      if (response.status >= 500) {
        return { success: false, errorMessage: 'Server error. Please try again later.' };
      }

      return { success: false, errorMessage: 'Registration failed. Please try again.' };
    } catch {
      return { success: false, errorMessage: 'Cannot connect to server. Check your internet connection.' };
    }
  };

  return (
    <AuthContext.Provider value={{
      currentUser,
      isAuthenticated,
      login,
      logout,
      register
    }}>
      {children}
    </AuthContext.Provider>
  );
};

export const useAuth = () => {
  const context = useContext(AuthContext);
  if (context === undefined) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
};