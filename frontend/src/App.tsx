import { Navigate, Route, Routes } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useMe } from './api/hooks';
import { AthleteProvider } from './athleteContext';
import { Layout } from './components/Layout';
import { LoginPage } from './pages/LoginPage';
import { AthletesPage } from './pages/AthletesPage';
import { ImportsPage } from './pages/ImportsPage';
import { CoveragePage } from './pages/CoveragePage';
import { CalibrationPage } from './pages/CalibrationPage';

export function App() {
  const { t } = useTranslation();
  const { data: me, isLoading } = useMe();

  if (isLoading) return <p className="center muted">{t('common.loading')}</p>;
  if (!me) return <LoginPage />;

  return (
    <AthleteProvider>
      <Layout>
        <Routes>
          <Route path="/" element={<Navigate to="/coverage" replace />} />
          <Route path="/athletes" element={<AthletesPage />} />
          <Route path="/imports" element={<ImportsPage />} />
          <Route path="/coverage" element={<CoveragePage />} />
          <Route path="/calibration" element={<CalibrationPage />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </Layout>
    </AthleteProvider>
  );
}
