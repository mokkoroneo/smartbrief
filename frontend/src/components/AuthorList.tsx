import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import Footer from './Footer';
import { theme } from '../theme';
import { apiClient } from '../utils/apiClient';
import { useToast } from '../contexts/ToastContext';
import { useAuthStore } from '../store/authStore';

interface AuthorListProps {
  onBack: () => void;
}

// ★38名分の完璧な画像マッピング
const AUTHOR_IMAGES: Record<string, string> = {
  // グループA: 幻想・ロマン
  '宮沢 賢治': 'miyazawa_kenji.webp',
  '小川 未明': 'ogawa_mimei.webp',
  '泉 鏡花': 'izumi_kyoka.webp',
  '萩原 朔太郎': 'hagiwara_sakutarou.webp',
  '堀 辰雄': 'hori_tatsuo.webp',
  '中原 中也': 'nakahara_chuya.webp',
  '牧野 信一': 'makino_shinichi.webp',
  '豊島 与志雄': 'toyoshima_toshio.webp',
  // グループB: 無頼・近代
  '太宰 治': 'dazai_osamu.webp',
  '坂口 安吾': 'sakaguchi_ango.webp',
  '芥川 竜之介': 'akutagawa_ryunosuke.webp',
  '夏目 漱石': 'natsume_soseki.webp',
  '田山 録弥': 'tayama_rokuya.webp',
  '菊池 寛': 'kikuchi_kan.webp',
  '山本 周五郎': 'yamamoto_shugorou.webp',
  // グループC: 科学・思想・芸術
  '寺田 寅彦': 'terada_torahiko.webp',
  '中谷 宇吉郎': 'nakaya_ukichiro.webp',
  '北大路 魯山人': 'kitaooji_rosannzin.webp',
  '岡本 かの子': 'okamoto_kanoko.webp',
  '宮本 百合子': 'miyamoto_yuriko.webp',
  '伊藤 野枝': 'ito_noe.webp',
  '原 民喜': 'hara_tamiki.webp',
  '岸田 國士': 'kishida_kunio.png',
  '折口 信夫': 'origuchi_nobuo.webp',
  // グループD: エンタメ・ミステリー
  '江戸川 乱歩': 'edogawa_ranpo.webp',
  '夢野 久作': 'yumeno_kyusaku.webp',
  '海野 十三': 'uno_juza.webp',
  '国枝 史郎': 'kunieda_shiro.webp',
  '久生 十蘭': 'hisao_juran.webp',
  '岡本 綺堂': 'okamoto_kido.webp',
  '野村 胡堂': 'nomura_kodou.webp',
  '吉川 英治': 'yoshikawa_eiji.webp',
  '坂本 竜馬': 'sakamoto_ryoma.webp',
  '永井 荷風': 'nagai_kafu.webp',
  '新美 南吉': 'niimi_nankichi.webp',
  '今野 大力': 'konno_dairiki.webp',
  '佐藤 垢石': 'sato_kaseki.webp',
  '田中 貢太郎': 'tanaka_koutarou.webp',
  '長谷川 時雨': 'hasegawa_shigure.webp',
  '森 鴎外': 'mori_ogai.webp',
  '石川 啄木': 'ishikawa_takuboku.webp',
  '佐藤 春夫': 'sato_haruo.webp',
  '室生 犀星': 'muroo_saisei.webp',
  '林 芙美子': 'hayashi_fumiko.webp',
  '織田 作之助': 'oda_sakunosuke.webp',
  '横光 利一': 'yokomitsu_riichi.webp',
  '正岡 子規': 'masaoka_shiki.webp',
  '若山 牧水': 'wakayama_bokusui.webp',
  '種田 山頭火': 'taneda_santoka.webp',
  '柳田 国男': 'yanagita_kunio.webp',
  '福沢 諭吉': 'fukuzawa_yukichi.webp',
  '徳冨 蘆花': 'tokutomi_roka.webp',
  '田山 花袋': 'tayama_katai.webp',
  '直木 三十五': 'naoki_sanjugo.webp',
  '小栗 虫太郎': 'oguri_mushitaro.webp',
  '斎藤 茂吉': 'saito_mokichi.webp',
  '新渡戸 稲造': 'nitobe_inazo.webp',
  '内村 鑑三': 'uchimura_kanzo.webp',
  '伊藤 左千夫': 'ito_sachio.webp',
  '巌谷 小波': 'iwaya_sazanami.webp',
  '上村 松園': 'uemura_shoen.webp',
  '村山 籌子': 'murayama_choko.webp',
  '高村 光雲': 'takamura_koun.webp',
  '大町 桂月': 'omachi_keigetsu.webp',
  '奥 増夫': 'oku_masuo.webp',
  '小酒井 不木': 'kozakai_fuboku.webp',
  '大久保 ゆう': 'okubo_yu.webp',
  '桜間 中庸': 'sakurama_chuyo.webp',
  '平林 初之輔': 'hirabayashi_hatsunosuke.webp',
  '戸坂 潤': 'tosaka_jun.webp',
  '中井 正一': 'nakai_masakazu.webp',
  '喜田 貞吉': 'kida_sadakichi.webp',
  '神西 清': 'jinzai_kiyoshi.webp',
  '楠山 正雄': 'kusuyama_masao.webp',
  '槙村 浩': 'makimura_hiroshi.webp',
  '紫式部 ': 'murasaki_shikibu.webp',
  '薄田 泣菫': 'susukida_kyukin.webp',
  '和辻 哲郎': 'watsuji_tetsuro.webp',
  '木暮 理太郎': 'kogure_ritaro.webp',
  '竹内 浩三': 'takeuchi_kozo.webp',
  '佐々木 味津三': 'sasaki_mitsuzo.webp',
  '片山 広子': 'katayama_hiroko.webp',
  'アンデルセン ハンス・クリスチャン': 'andersen_hans_christian.webp',
  '森川 義信': 'morikawa_yoshinobu.webp',
  '北村 透谷': 'kitamura_tokoku.webp',
  '上田 敏': 'ueda_bin.webp',
  '富永 太郎': 'tominaga_taro.webp',
  '中里 介山': 'nakazato_kaizan.webp',
  '長塚 節': 'nagatsuka_takashi.webp',
  '北条 民雄': 'hojo_tamio.webp',
  'Dickens, Charles': 'dickens_charles.webp',
  'Wilde, Oscar': 'wilde_oscar.webp',
  'Chesterton, G. K. (Gilbert Keith)': 'chesterton_gk.webp',
  'Poe, Edgar Allan': 'poe_edgar_allan.webp',
  'Hardy, Thomas': 'hardy_thomas.webp',
  'London, Jack': 'london_jack.webp',
  'Plato': 'plato.webp',
  'Dostoyevsky, Fyodor': 'dostoyevsky_fyodor.webp',
  'Oppenheim, E. Phillips (Edward Phillips)': 'oppenheim_e_phillips.webp',
  'Xenophon': 'xenophon.webp',
  'Balzac, Honoré de': 'balzac_honore_de.webp',
  'Twain, Mark': 'twain_mark.webp',
  'Malory, Thomas, Sir': 'malory_thomas.webp',
  'MacDonald, George': 'macdonald_george.webp',
  'Buchan, John': 'buchan_john.webp',
  'James, Henry': 'james_henry.webp',
  'Davis, Rebecca Harding': 'davis_rebecca_harding.webp',
  'Brontë, Anne': 'bronte_anne.webp',
  'Congreve, William': 'congreve_william.webp',
  'Bannerman, Helen': 'bannerman_helen.webp',
  'Fitzgerald, F. Scott (Francis Scott)': 'fitzgerald_f_scott.webp',
  'Hesiod': 'hesiod.webp',
  'Rabelais, François': 'rabelais_francois.webp',
  'Haggard, H. Rider (Henry Rider)': 'haggard_h_rider.webp',
  'Burton, Richard Francis, Sir': 'burton_richard_francis.webp',
  'Johnson, Samuel': 'johnson_samuel.webp',
  'Gogol, Nikolai Vasilevich': 'gogol_nikolai.webp',
  'Tolstoy, Leo, graf': 'tolstoy_leo.webp',
  'Kipling, Rudyard': 'kipling_rudyard.webp',
  'Leroux, Gaston': 'leroux_gaston.webp',
  'Goldsmith, Oliver': 'goldsmith_oliver.webp',
  'Gross, Hans': 'gross_hans.webp',
  'Sidney, Philip': 'sidney_philip.webp',
  'Eliot, T. S. (Thomas Stearns)': 'eliot_ts.webp',
  'Wells, H. G. (Herbert George)': 'wells_hg.webp',
  'Shaw, Bernard': 'shaw_bernard.webp',
  'Lindsay, Vachel': 'lindsay_vachel.webp',
  'Ibsen, Henrik': 'ibsen_henrik.webp',
  'Milne, A. A. (Alan Alexander)': 'milne_aa.webp',
  'Mandeville, John, Sir': 'mandeville_john.webp',
  'Chekhov, Anton Pavlovich': 'chekhov_anton.webp',
  'Paine, Thomas': 'paine_thomas.webp',
  'Clausewitz, Carl von': 'clausewitz_carl_von.webp',
  'Dante Alighieri': 'dante_alighieri.webp',
  'Whitman, Walt': 'whitman_walt.webp',
  'Chopin, Kate': 'chopin_kate.webp',
  'Verne, Jules': 'verne_jules.webp',
  'Thoreau, Henry David': 'thoreau_henry_david.webp',
  'Nesbit, E. (Edith)': 'nesbit_e.webp',
  'Burnett, Frances Hodgson': 'burnett_frances_hodgson.webp',
  'Rand, Ayn': 'rand_ayn.webp',
  'Smiles, Samuel': 'smiles_samuel.webp',
  'Walpole, Horace': 'walpole_horace.webp',
  'Synge, J. M. (John Millington)': 'synge_jm.webp',
  'Sewell, Anna': 'sewell_anna.webp',
  'Marlowe, Christopher': 'marlowe_christopher.webp',
  'Collodi, Carlo': 'collodi_carlo.webp',
  'Apollonius, Rhodius': 'apollonius_rhodius.webp',
  'Scott, Walter': 'scott_walter.webp',
  'More, Thomas, Saint': 'more_thomas.webp',
  'Saxo, Grammaticus': 'saxo_grammaticus.webp',
  'Carlyle, Thomas': 'carlyle_thomas.webp',
  'Galsworthy, John': 'galsworthy_john.webp',
  'Lang, Andrew': 'lang_andrew.webp',
  'Browning, Elizabeth Barrett': 'browning_elizabeth_barrett.webp',
  'Doyle, Arthur Conan': 'doyle_arthur_conan.webp',
  '三好 達治': 'miyoshi_tatsuji.webp',
  '与謝野 晶子': 'yosano_akiko.webp',
  '中島 敦': 'nakajima_atsushi.webp',
  '久米 正雄': 'kume_masao.webp',
  '二葉亭 四迷': 'futabatei_shimei.webp',
  '北原 白秋': 'kitahara_hakushu.webp',
  '南方 熊楠': 'minakata_kumagusu.webp',
  '国木田 独歩': 'kunikida_doppo.webp',
  '坪内 逍遥': 'tsubouchi_shoyo.webp',
  '宇野 浩二': 'uno_koji.webp',
  '小林 多喜二': 'kobayashi_takiji.webp',
  '小泉 八雲': 'koizumi_yakumo.webp',
  '尾崎 紅葉': 'ozaki_koyo.webp',
  '岡倉 天心': 'okakura_tenshin.webp',
  '岸田 国士': 'kishida_kunio.webp',
  '島崎 藤村': 'shimazaki_toson.webp',
  '幸田 露伴': 'koda_rohan.webp',
  '徳永 直': 'tokunaga_sunao.webp',
  '徳田 秋声': 'tokuda_shusei.webp',
  '有島 武郎': 'arishima_takeo.webp',
  '梶井 基次郎': 'kajii_motojiro.webp',
  '樋口 一葉': 'higuchi_ichiyo.webp',
  '田村 俊子': 'tamura_toshiko.webp',
  '立原 道造': 'tachihara_michizo.webp',
  '竹久 夢二': 'takehisa_yumeji.webp',
  '葉山 嘉樹': 'hayama_yoshiki.webp',
  '谷崎 潤一郎': 'tanizaki_junichiro.webp',
  '高村 光太郎': 'takamura_koutarou.webp',
  '高浜 虚子': 'takahama_kyoshi.webp',
};

const AuthorList: React.FC<AuthorListProps> = ({ onBack }) => {
  const { isLoggedIn, logout } = useAuthStore();
  const navigate = useNavigate();
  const { showToast } = useToast();
  
  const [authors, setAuthors] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!isLoggedIn) return;

    const loadAuthors = async () => {
      setLoading(true);
      const res = await apiClient.get<string[]>('/books/authors/all');
      
      if (!res.ok) {
        showToast(res.message || 'エラーが発生しました', 'error');
        if (res.status === 401) logout();
        setLoading(false);
        return;
      }
      setAuthors(res.data || []);
      setLoading(false);
    };

    loadAuthors();
  }, [logout, showToast, isLoggedIn]);

  const handleAuthorClick = (authorName: string) => {
    navigate(`/search?q=${encodeURIComponent(authorName)}`);
  };

  if (loading) return (
    <div style={styles.loadingContainer}>
      <div style={styles.spinner}></div>
      <p style={styles.loadingText}>作家名簿を検索中...</p>
    </div>
  );

  return (
    <div style={styles.wrapper}>
      <nav style={styles.navBar}>
        <button onClick={onBack} style={styles.backButton}>
           <span style={{fontSize:'18px'}}>←</span> ダッシュボードへ
        </button>
        <div style={styles.navTitle}>作家一覧</div>
        <div style={{width:'80px'}}></div>
      </nav>

      <main style={styles.paperContainer}>
        <header style={styles.header}>
            <span style={styles.headerIcon}>✒️</span>
            <h2 style={styles.title}>収録作家一覧</h2>
            <p style={styles.sub}>全 {authors.length} 名の作家を収蔵しています</p>
        </header>

        {/* 1.7万件に耐える効率的なグリッド */}
        <div style={styles.grid}>
          {authors.map((author, index) => {
            // ★名前から画像ファイル名を取得
            const imageFile = AUTHOR_IMAGES[author];

            return (
              <button 
                key={index} 
                style={styles.authorCard}
                onClick={() => handleAuthorClick(author)}
              >
                {/* 図書カードのインデックス番号 */}
                <div style={styles.cardIndex}>{index + 1}</div>

                {/* ★画像がある場合は肖像画を、ない場合はアイコンを表示 */}
                <div style={styles.portraitWrapper}>
                  {imageFile ? (
                    <img 
                      src={`https://assets.smartbrief.jp/${imageFile}`} 
                      alt={author} 
                      style={styles.portraitImage} 
                    />
                  ) : (
                    <div style={styles.defaultIcon}>✒️</div>
                  )}
                </div>

                <span style={styles.name}>{author}</span>
                <div style={styles.cardLine}></div>
              </button>
            );
          })}
        </div>

        <div style={styles.footerArea}>
           <Footer color={theme.colors.textSub} separatorColor={theme.colors.border} />
        </div>
      </main>
    </div>
  );
};

const styles: { [key: string]: React.CSSProperties } = {
  wrapper: { minHeight: '100vh', backgroundColor: theme.colors.background, color: theme.colors.textMain, fontFamily: theme.fonts.body, paddingBottom: '40px' },
  navBar: { display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '15px 20px', position: 'sticky', top: 0, zIndex: 100, backgroundColor: 'rgba(252, 249, 242, 0.95)', borderBottom: `1px solid ${theme.colors.border}`, backdropFilter: 'blur(5px)' },
  backButton: { background: 'none', border: 'none', cursor: 'pointer', color: theme.colors.textSub, fontSize: '14px', fontWeight: 'bold', display: 'flex', alignItems: 'center', gap: '5px', fontFamily: theme.fonts.heading },
  navTitle: { fontSize: '14px', fontWeight: 'bold', color: theme.colors.textMain, fontFamily: theme.fonts.heading },
  paperContainer: { maxWidth: '1000px', margin: '30px auto', backgroundColor: '#fff', borderRadius: '4px', boxShadow: '0 2px 5px rgba(0,0,0,0.05), 0 10px 30px rgba(0,0,0,0.08)', borderTop: `6px solid ${theme.colors.primary}`, padding: '40px 20px', minHeight: '600px' },
  header: { textAlign: 'center', marginBottom: '40px' },
  headerIcon: { fontSize: '32px', display: 'block', marginBottom: '10px' },
  title: { fontSize: '24px', color: theme.colors.primary, fontFamily: theme.fonts.heading, marginBottom: '8px', letterSpacing: '0.1em' },
  sub: { color: theme.colors.textSub, fontSize: '13px', fontFamily: theme.fonts.body },
  
  // 最小幅を140pxに絞り、より多くのカードを1行に詰め込む
  grid: { 
    display: 'grid', 
    gridTemplateColumns: 'repeat(auto-fill, minmax(140px, 1fr))', 
    gap: '12px', 
    marginBottom: '60px' 
  },
  
  // 図書カード風のデザイン
  authorCard: { 
    position: 'relative',
    backgroundColor: '#fdfbf7', // わずかに紙のような色
    border: `1px solid ${theme.colors.border}`, 
    borderRadius: '4px', 
    padding: '20px 10px 15px', 
    cursor: 'pointer', 
    display: 'flex', 
    flexDirection: 'column', 
    alignItems: 'center', 
    justifyContent: 'center', 
    transition: 'transform 0.1s ease, box-shadow 0.1s ease',
    overflow: 'hidden'
  },
  cardIndex: { position: 'absolute', top: '5px', left: '5px', fontSize: '9px', color: '#ccc', fontFamily: 'serif' },
  
  // ★追加：肖像画用コンテナのスタイル
  portraitWrapper: {
    width: '56px',
    height: '56px',
    borderRadius: '50%',
    overflow: 'hidden',
    marginBottom: '10px',
    border: `2px solid ${theme.colors.accent}`,
    backgroundColor: '#fff',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    boxShadow: '0 2px 6px rgba(0,0,0,0.08)'
  },
  portraitImage: { width: '100%', height: '100%', objectFit: 'cover' },
  defaultIcon: { fontSize: '20px', opacity: 0.3 },

  name: { fontWeight: 'bold', color: theme.colors.textMain, fontSize: '14px', fontFamily: theme.fonts.heading, textAlign: 'center', zIndex: 2 },
  cardLine: { width: '40px', height: '1px', backgroundColor: theme.colors.accent, marginTop: '8px', opacity: 0.6 },

  footerArea: { borderTop: `1px solid ${theme.colors.border}`, paddingTop: '20px' },
  loadingContainer: { height: '80vh', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', backgroundColor: theme.colors.background },
  spinner: { width: '40px', height: '40px', border: '3px solid #eee', borderTop: `3px solid ${theme.colors.primary}`, borderRadius: '50%', animation: 'spin 1s linear infinite' },
  loadingText: { marginTop: '20px', fontFamily: theme.fonts.heading, color: theme.colors.textSub },
};

export default AuthorList;