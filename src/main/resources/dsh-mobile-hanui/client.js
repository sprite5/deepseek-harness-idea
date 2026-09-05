/**
 * Mobile shell v18 — fixed left-edge menu button + DSH drawer icon.
 *
 * Root cause of “only menu visible” (v11): `grid-template-columns: 0 1fr 0` +
 * `position:fixed` on sidebar/details removes them from grid flow → center
 * auto-places into track 1 (width 0). Fix: `grid-column: 2` on centerCol.
 *
 * v18 changes:
 * - Menu button (.dshMobMenu) pinned to the LEFT edge (CSS fixed left:8px);
 *   no longer draggable — pure tap-to-open, icon swapped from the FishLogo
 *   to the DSH sidebar/drawer glyph (16x16, same as dsh chrome).
 *
 * Retained rules:
 * - Pin .centerCol to grid-column:2 / grid-row:1 under mobile shell
 * - Never transform the center column; never lock html/body/#root height
 * - Drawer from the LEFT; open via menu button click/tap (no swipe-open)
 * - Close: backdrop tap + swipe-left on backdrop; no X button
 * - Main grid stays 0 1fr 0 so local page does not move/squeeze
 * - Header: AgentPreset「模式」beside 轨迹 tabs (title row free)
 * - Session tap auto-close: YDXeBa_sessionRow / searchResultRow (+ 140/280ms)
 * - Plugin only (dsh.client)
 */
window.__ModuleLoader__.load({
  id: 'dsh-mobile-hanui',
  factory: (require) => {
    const module = { exports: {} }
    const exports = module.exports
    Object.defineProperty(exports, Symbol.toStringTag, { value: 'Module' })

    const React = require('react')
    const { jsx, jsxs } = require('react/jsx-runtime')

    const MOBILE_MQ = '(max-width: 1023px)'
    const STYLE_ID = 'dsh-mobile-hanui-css-v1'
    const HTML_CLASS = 'dsh-mobile-shell'
    const ATTR_DETAILS = 'data-dsh-mobile-details-open'
    // Swipe-close only (backdrop); axis-lock aborts vertical pans
    const SWIPE_AXIS_LOCK = 12
    const SWIPE_DX_MIN = 48
    const SWIPE_DX_DY = 1.5
    // Current @deepseek-ai/dsh-client-ui-layout AppFrame module classes
    const CLS = {
      frame: 'pI_x6G_frame',
      sidebar: 'pI_x6G_sidebarCol',
      center: 'pI_x6G_centerCol',
      details: 'pI_x6G_detailsCol',
      overlay: 'pI_x6G_overlayLayer',
      chatScroll: 'Md3f7G_scroll',
      chatOlder: 'Md3f7G_older',
    }
    const MSG = {
      actions: 'p-xYUq_actions',
      action: 'p-xYUq_action',
      timeStart: 'p-xYUq_timeStart',
      timeEnd: 'p-xYUq_timeEnd',
      runTimeDot: 'p-xYUq_runTimeDot',
    }
    const STATS = { root: 'FJxK0a_root', sep: 'FJxK0a_sep' }
    const INPUT = {
      root: 'uV2eYG_root',
      card: 'uV2eYG_card',
      row: 'uV2eYG_row',
      modes: 'uV2eYG_modes',
      select: 'uV2eYG_select',
      tools: 'uV2eYG_tools',
      trailing: 'uV2eYG_trailing',
      primary: 'uV2eYG_primary',
      add: 'uV2eYG_add',
      input: 'uV2eYG_input',
    }
    // Conversation session header (title / 轨迹 / AgentPreset「模式」)
    const HDR = {
      header: 'wSkVaW_header',
      titleRow: 'wSkVaW_titleRow',
      titleCluster: 'wSkVaW_titleCluster',
      crumbs: 'wSkVaW_crumbs',
      headerActions: 'wSkVaW_headerActions',
      headerUtilities: 'wSkVaW_headerUtilities',
      tabs: 'wSkVaW_tabs',
    }
    const PRESET_LABEL = 'SVAs4q_label'
    // Subagent catalog (header actions trigger + dropdown menu)
    const SUBAGENT = {
      root: 'h8S2Va_root',
      trigger: 'h8S2Va_trigger',
      menu: 'h8S2Va_menu',
    }
    // User-questions panel (ask_user_question composer takeover)
    const QUESTION = {
      frame: 'Mbwy4a_frame',
      card: 'Mbwy4a_card',
      options: 'Mbwy4a_options',
      body: 'Mbwy4a_body',
      footer: 'Mbwy4a_footer',
    }
    // New-session / hero composer (choose workspace, glow, workspace row)
    const HERO = {
      composerHero: 'wSkVaW_composerHero',
      heroGlow: 'wSkVaW_heroGlow',
      heroWorkspaceRow: 'wSkVaW_heroWorkspaceRow',
      scrollBody: 'wSkVaW_scrollBody',
      root: 'wSkVaW_root',
      shellRoot: 'pXSMma_root',
      shellStack: 'pXSMma_stack',
      shellBody: 'pXSMma_body',
    }
    // Workspace section header — the row directly beside the workspace title:
    // search field, view-options (group/order) menu, and add-workspace (+)
    // button. These controls open sub-views / menus inside the drawer, so a tap
    // must NOT collapse the sidebar (previously the generic "actionable" branch
    // closed it → search / add-workspace flashed the drawer closed).
    const WS_HDR = {
      sectionHeader: 'qDHVXG_sectionHeader',
      search: 'qDHVXG_search',
      searchButton: 'qDHVXG_searchButton',
      searchInput: 'qDHVXG_searchInput',
      clearButton: 'qDHVXG_clearButton',
      iconButton: 'qDHVXG_iconButton',
      headerActions: 'qDHVXG_headerActions',
    }
    // Workspace sidebar session rows (div[role=treeitem], not <button>)
    const SIDEBAR_ROW = {
      session: 'YDXeBa_sessionRow',
      search: 'YDXeBa_searchResultRow',
      project: 'YDXeBa_projectRow',
      rowActions: 'YDXeBa_rowActions',
      iconButton: 'YDXeBa_iconButton',
    }
    // Tooltip bubble (hover label like 停止生成/发送消息/关闭) — position:fixed
    // with hover-time coordinates that don't follow the anchor when it moves.
    const TOOLTIP_BUBBLE = '_bubble_owhem_8'
    // Sidebar inner root (SidebarRoot) — carries an inline width from the desktop
    // three-column layout that is narrower than the phone drawer, leaving a
    // blank strip to the right of the collapse button.
    const SIDEBAR_ROOT = 'hHd-Xa_root'
    // Settings panel (two-column 800px sheet) — breaks on phones unless turned
    // into a full-screen single column.
    const SETTINGS = {
      overlay: 'VOzbGW_overlay',
      panel: 'VOzbGW_panel',
      nav: 'VOzbGW_nav',
      navCell: 'VOzbGW_navCell',
      navTitle: 'VOzbGW_navTitle',
      content: 'VOzbGW_content',
      options: 'VOzbGW_options',
      trigger: 'VOzbGW_trigger',
    }

    function shellDisabled() {
      try {
        if (typeof location === 'undefined') return false
        if (new URLSearchParams(location.search).get('mobileShell') === '0') return true
        if (localStorage.getItem('dsh-mobile-shell') === '0') return true
      } catch (_) {}
      return false
    }

    const CSS = `
@media ${MOBILE_MQ} {
  /* Local chat: full width, never translated / squeezed */
  html.${HTML_CLASS} .${CLS.frame} {
    grid-template-columns: 0 minmax(0, 1fr) 0 !important;
    transition: none !important;
    transform: none !important;
  }

  html.${HTML_CLASS} .${CLS.frame} > [data-side] {
    display: none !important;
  }

  /* CENTER — pin to track 2 (1fr) so fixed side columns cannot starve chat */
  html.${HTML_CLASS} .${CLS.center} {
    grid-column: 2 !important;
    grid-row: 1 !important;
    display: flex !important;
    visibility: visible !important;
    opacity: 1 !important;
    position: relative !important;
    inset: auto !important;
    top: auto !important;
    right: auto !important;
    bottom: auto !important;
    left: auto !important;
    width: auto !important;
    max-width: none !important;
    height: 100% !important; /* keep the grid track's full height so the
                               conversation root's height:100% chain survives —
                               without it the composer scrolls out of view */
    align-self: stretch !important;
    min-height: 0 !important;
    min-width: 0 !important;
    margin: 0 !important;
    transform: none !important;
    translate: none !important;
    pointer-events: auto !important;
    overflow: visible !important; /* never clip the model/effort dropdowns that
                                    pop up or out of the center column */
    z-index: 1 !important;
  }

  /* Overlay stays a full-frame absolute layer; never steal the center track */
  html.${HTML_CLASS} .${CLS.overlay} {
    grid-column: 1 / -1 !important;
    grid-row: 1 !important;
  }

  /* SIDEBAR — left drawer; closed parked off-screen to the LEFT (not on center) */
  html.${HTML_CLASS} .${CLS.sidebar} {
    position: fixed !important;
    z-index: 50 !important;
    top: 0 !important;
    left: 0 !important;
    right: auto !important;
    bottom: 0 !important;
    width: min(100%, 360px) !important;
    max-width: 100% !important;
    height: 100% !important;
    height: 100dvh !important;
    margin: 0 !important;
    box-sizing: border-box !important;
    padding-top: env(safe-area-inset-top, 0px);
    padding-bottom: env(safe-area-inset-bottom, 0px);
    background: var(--dsw-specific-sidebar-fill, var(--dsw-alias-bg-base, #fff)) !important;
    border: none !important;
    border-right: 1px solid var(--dsw-alias-border-l2, rgba(0,0,0,.08)) !important;
    overflow: auto !important;
    -webkit-overflow-scrolling: touch;
    overscroll-behavior: contain;
    transform: translate3d(-100%, 0, 0) !important;
    pointer-events: none !important;
    transition: transform 0.28s cubic-bezier(0.32, 0.72, 0, 1);
  }

  html.${HTML_CLASS} .${CLS.frame}:not([data-sidebar-collapsed]) .${CLS.sidebar} {
    transform: translate3d(0, 0, 0) !important;
    pointer-events: auto !important;
  }

  html.${HTML_CLASS} .${CLS.frame}:not([data-sidebar-collapsed]) .${CLS.sidebar} > * {
    width: 100% !important;
    max-width: none !important;
    animation: none !important;
  }
  /* The sidebar's inner root carries an inline width from the desktop
     three-column solve (e.g. 280px) that is narrower than the phone drawer
     (min(100%,360px)), leaving a blank strip right of the collapse button.
     Force the inner root to fill the drawer. */
  html.${HTML_CLASS} .${CLS.frame}:not([data-sidebar-collapsed]) .${SIDEBAR_ROOT} {
    width: 100% !important;
    max-width: 100% !important;
    box-sizing: border-box !important;
  }
  html.${HTML_CLASS} .${CLS.frame}:not([data-sidebar-collapsed]) .${CLS.sidebar} [class*="Label"] {
    max-width: none !important;
    opacity: 1 !important;
    overflow: visible !important;
  }
  html.${HTML_CLASS} .${CLS.frame}:not([data-sidebar-collapsed]) .${CLS.sidebar} time,
  html.${HTML_CLASS} .${CLS.frame}:not([data-sidebar-collapsed]) .${CLS.sidebar} [class*="time"],
  html.${HTML_CLASS} .${CLS.frame}:not([data-sidebar-collapsed]) .${CLS.sidebar} [class*="Time"],
  html.${HTML_CLASS} .${CLS.frame}:not([data-sidebar-collapsed]) .${CLS.sidebar} [class*="meta"],
  html.${HTML_CLASS} .${CLS.frame}:not([data-sidebar-collapsed]) .${CLS.sidebar} [class*="Meta"] {
    flex: none;
    white-space: nowrap;
    position: static !important;
    transform: none !important;
    opacity: 1 !important;
  }

  /* DETAILS — left slide sheet for tool-row clicks */
  html.${HTML_CLASS} .${CLS.details} {
    position: fixed !important;
    z-index: 55 !important;
    top: 0 !important;
    left: 0 !important;
    right: auto !important;
    bottom: 0 !important;
    width: 100% !important;
    max-width: 100% !important;
    height: 100% !important;
    height: 100dvh !important;
    margin: 0 !important;
    box-sizing: border-box !important;
    padding-top: env(safe-area-inset-top, 0px);
    padding-bottom: env(safe-area-inset-bottom, 0px);
    background: var(--dsw-alias-bg-base, #fff) !important;
    border: none !important;
    overflow: auto !important;
    -webkit-overflow-scrolling: touch;
    overscroll-behavior: contain;
    transform: translate3d(-100%, 0, 0) !important;
    pointer-events: none !important;
    transition: transform 0.28s cubic-bezier(0.32, 0.72, 0, 1);
  }

  html.${HTML_CLASS}[${ATTR_DETAILS}] .${CLS.details} {
    transform: translate3d(0, 0, 0) !important;
    pointer-events: auto !important;
  }

  html.${HTML_CLASS}[${ATTR_DETAILS}] .${CLS.details} > * {
    width: 100% !important;
    max-width: none !important;
    min-height: 100%;
  }

  @media (prefers-reduced-motion: reduce) {
    html.${HTML_CLASS} .${CLS.sidebar},
    html.${HTML_CLASS} .${CLS.details} {
      transition: none !important;
    }
  }

  /* Message footer — line1 icons+time; line2 metrics (runMs inside timeEnd) */
  html.${HTML_CLASS} .${MSG.actions} {
    display: flex !important;
    flex-direction: row !important;
    flex-wrap: nowrap !important;
    align-items: center !important;
    height: auto !important;
    min-height: 20px !important;
    gap: 2px 4px !important;
    row-gap: 2px !important;
    font-size: 11px !important;
  }
  html.${HTML_CLASS} .${MSG.actions} button,
  html.${HTML_CLASS} [class*='p-xYUq_action'],
  html.${HTML_CLASS} [class*='_8_XoUG_action'] {
    width: 20px !important;
    height: 20px !important;
    min-width: 20px !important;
    min-height: 20px !important;
    padding: 2px !important;
  }
  html.${HTML_CLASS} .${MSG.actions} svg,
  html.${HTML_CLASS} [class*='p-xYUq_action'] svg,
  html.${HTML_CLASS} [class*='_8_XoUG_action'] svg {
    width: 12px !important;
    height: 12px !important;
  }
  html.${HTML_CLASS} .${MSG.timeStart},
  html.${HTML_CLASS} .${MSG.timeEnd} {
    display: contents !important;
  }
  html.${HTML_CLASS} .${MSG.runTimeDot}:first-of-type {
    flex: 0 0 100% !important;
    width: 100% !important;
    height: 0 !important;
    margin: 0 !important;
    opacity: 0 !important;
    overflow: hidden !important;
    pointer-events: none !important;
  }
  html.${HTML_CLASS} .${MSG.runTimeDot}:not(:first-of-type) {
    margin: 0 6px !important;
    font-size: 12px !important;
  }
  /* StatsLine composer dock — force all metrics onto one compact row. */
  html.${HTML_CLASS} .${STATS.root} {
    white-space: nowrap !important;
    overflow: hidden !important;
    text-overflow: ellipsis !important;
    display: flex !important;
    flex-wrap: nowrap !important;
    justify-content: center !important;
    align-items: baseline !important;
    gap: 0 4px !important;
    row-gap: 0 !important;
    max-width: 100% !important;
    font-size: 12px !important;
    line-height: 18px !important;
    text-align: center !important;
    opacity: 0.8;
  }
  html.${HTML_CLASS} .${STATS.root} [data-dsh-stats="speeds"],
  html.${HTML_CLASS} .${STATS.root} [data-dsh-stats="sep-hide"] {
    display: none !important;
  }
  html.${HTML_CLASS} .${STATS.root} [data-dsh-stats-break] {
    display: none !important;
  }

  /* InputBar: left at DSH default (no compact overrides — avoid breaking the mirror-based caret/auto-grow logic). */

  /* Session header: title alone; 「模式」beside 轨迹 tabs */
  html.${HTML_CLASS} .${HDR.header} {
    display: grid !important;
    grid-template-columns: minmax(0, 1fr) auto !important;
    grid-template-areas:
      "crumbs utilities"
      "tabs actions" !important;
    align-items: center !important;
    column-gap: 4px !important;
    row-gap: 1px !important;
    padding: 3px 8px 2px !important;
    min-height: 0 !important;
  }
  /* Compact top/session header: preserve the controls, remove excess chrome. */
  html.${HTML_CLASS} .${HDR.header} button,
  html.${HTML_CLASS} .${HDR.header} [role="button"] {
    min-height: 22px !important;
    height: 22px !important;
    padding: 1px 5px !important;
    font-size: 11px !important;
    line-height: 18px !important;
  }
  html.${HTML_CLASS} .${HDR.crumbs},
  html.${HTML_CLASS} .${HDR.crumbs} * {
    font-size: 12px !important;
    line-height: 16px !important;
  }
  html.${HTML_CLASS} .${HDR.tabs},
  html.${HTML_CLASS} .${HDR.tabs} * {
    font-size: 11px !important;
    line-height: 20px !important;
  }
  html.${HTML_CLASS} .${HDR.headerUtilities},
  html.${HTML_CLASS} .${HDR.headerUtilities} * {
    font-size: 10px !important;
    line-height: 18px !important;
  }
  html.${HTML_CLASS} .${HDR.titleRow},
  html.${HTML_CLASS} .${HDR.titleCluster} {
    display: contents !important;
  }
  html.${HTML_CLASS} .${HDR.crumbs} {
    grid-area: crumbs !important;
    min-width: 0 !important;
    max-width: 100% !important;
  }
  html.${HTML_CLASS} .${HDR.headerUtilities} {
    grid-area: utilities !important;
    justify-self: end !important;
  }
  html.${HTML_CLASS} .${HDR.tabs} {
    grid-area: tabs !important;
    justify-self: start !important;
    min-width: 0 !important;
  }
  html.${HTML_CLASS} .${HDR.headerActions} {
    grid-area: actions !important;
    justify-self: end !important;
    align-self: center !important;
    min-width: 0 !important;
    max-width: 100% !important;
    flex-wrap: wrap !important;
    gap: 4px 8px !important;
    overflow: visible !important; /* don't clip subagent/jobs buttons or their menus */
  }
  html.${HTML_CLASS} .${HDR.headerActions} .${PRESET_LABEL} {
    display: inline-flex !important;
    align-items: center !important;
    gap: 4px !important;
    max-width: 100% !important;
    overflow: hidden !important;
    text-overflow: ellipsis !important;
    white-space: nowrap !important;
    font-size: 12px !important;
    line-height: 18px !important;
  }

  /* Subagent catalog: keep the trigger visible and pull its dropdown out of
     the (previously clipped) header so the child-agent list is reachable. */
  html.${HTML_CLASS} .${SUBAGENT.root} {
    position: static !important;
  }
  html.${HTML_CLASS} .${SUBAGENT.menu} {
    position: fixed !important;
    top: env(safe-area-inset-top, 0px) !important;
    left: 8px !important;
    right: 8px !important;
    width: auto !important;
    max-width: none !important;
    max-height: min(70vh, 560px) !important;
    z-index: 120 !important;
  }

  /* User-questions panel: on phones it renders inside the sticky composer seat,
     where narrow height / overflow clipping leaves it half-visible or hidden.
     Lift it into a full-screen centered dialog with a mask so options are
     always reachable (desktop unchanged). */
  html.${HTML_CLASS} .${QUESTION.frame} {
    display: flex !important;
    visibility: visible !important;
    opacity: 1 !important;
    position: fixed !important;
    inset: 0 !important;
    z-index: 300 !important;
    align-items: center !important;
    justify-content: center !important;
    padding: 12px !important;
    box-sizing: border-box !important;
    max-height: none !important;
    overflow: auto !important;
    -webkit-overflow-scrolling: touch;
    background: var(--dsw-alias-bg-mask-1, rgba(15,17,21,.5)) !important;
  }
  html.${HTML_CLASS} .${QUESTION.card} {
    width: 100% !important;
    max-width: min(560px, 100%) !important;
    max-height: min(85vh, 85dvh) !important;
    box-sizing: border-box !important;
    overflow: hidden !important;
    display: flex !important;
    flex-direction: column !important;
    background: var(--dsw-alias-bg-layer-2, var(--dsw-alias-bg-base, #fff)) !important;
    border-radius: 20px !important;
    box-shadow: var(--dsw-shadow-lv3, 0 12px 40px rgba(0,0,0,.2)) !important;
  }
  html.${HTML_CLASS} .${QUESTION.body} {
    flex: 1 1 auto !important;
    overflow-y: auto !important;
    -webkit-overflow-scrolling: touch;
    min-height: 0 !important;
  }
  html.${HTML_CLASS} .${QUESTION.footer} {
    flex: none !important;
  }

  /* New-session hero composer: full-width column that fills the viewport so
     the centered title stays mid-screen while the input bar docks at the
     bottom like a normal conversation (no giant blank gap below it). */
  html.${HTML_CLASS} .${HERO.composerHero} {
    width: 100% !important;
    max-width: 100% !important;
    box-sizing: border-box !important;
    display: flex !important;
    flex-direction: column !important;
    min-height: 100% !important;
    padding-left: 8px !important;
    padding-right: 8px !important;
    padding-bottom: 16px !important;
    gap: 8px !important;
  }
  /* Title block (HeroShell) absorbs the extra vertical space and centers the
     headline inside it, keeping logo/text visually centered. */
  html.${HTML_CLASS} .${HERO.composerHero} .${HERO.shellRoot} {
    flex: 1 1 auto !important;
    min-height: 0 !important;
    height: auto !important;
    justify-content: center !important;
    padding: 24px 16px !important;
  }
  html.${HTML_CLASS} .${HERO.composerHero} .${HERO.shellStack} {
    max-width: 100% !important;
    width: 100% !important;
  }
  /* The input bar and workspace row stay flex:none at the bottom. */
  html.${HTML_CLASS} .${HERO.composerHero} .${INPUT.root} {
    flex: none !important;
    width: 100% !important;
    max-width: 100% !important;
  }
  html.${HTML_CLASS} .${HERO.heroGlow} {
    width: 100% !important;
    max-width: 100vw !important;
    opacity: 0.5 !important;
    left: 0 !important;
    right: 0 !important;
    transform: none !important;
  }
  html.${HTML_CLASS} .${HERO.heroWorkspaceRow} {
    box-sizing: border-box !important;
    padding-left: 8px !important;
    padding-right: 8px !important;
    flex-wrap: wrap !important;
    min-width: 0 !important;
    flex: none !important;
  }

  /* Tooltip bubbles (停止生成/发送消息/关闭 hover labels) are position:fixed
     with hover-time coordinates, so they don't follow their anchor when the
     composer/drawer scrolls or reflows on phones. Hide them on mobile — touch
     has no hover anyway, and aria-labels keep the text available to AT. */
  html.${HTML_CLASS} .${TOOLTIP_BUBBLE} {
    display: none !important;
  }

  /* Settings panel: desktop is an 800px two-column sheet (188px nav + content).
     On phones turn it full-screen single-column so the nav becomes a top strip
     and the content fills the viewport instead of overflowing / glitching. */
  html.${HTML_CLASS} .${SETTINGS.panel} {
    width: 100vw !important;
    max-width: 100vw !important;
    height: 100dvh !important;
    max-height: 100dvh !important;
    border-radius: 0 !important;
    flex-direction: column !important;
  }
  html.${HTML_CLASS} .${SETTINGS.panel} .${SETTINGS.nav} {
    flex-direction: row !important;
    flex-wrap: nowrap !important;
    flex: none !important;
    width: 100% !important;
    gap: 4px !important;
    padding: 10px 12px !important;
    overflow-x: auto !important;
    overflow-y: hidden !important;
    -webkit-overflow-scrolling: touch;
    scrollbar-width: none !important;
  }
  /* Each nav cell stays a fixed pill so the strip scrolls horizontally. */
  html.${HTML_CLASS} .${SETTINGS.panel} .${SETTINGS.nav} .${SETTINGS.navCell} {
    flex: 0 0 auto !important;
    width: auto !important;
    white-space: nowrap !important;
  }
  /* navTitle + navList lay out in a single scrollable row. */
  html.${HTML_CLASS} .${SETTINGS.panel} .${SETTINGS.nav} > div {
    flex-direction: row !important;
    flex-wrap: nowrap !important;
    flex: 0 0 auto !important;
    gap: 4px !important;
  }
  /* Hide the "设置" nav title on phones — the horizontally scrollable nav
     cells are self-explanatory and the title wastes vertical space. */
  html.${HTML_CLASS} .${SETTINGS.panel} .${SETTINGS.navTitle} {
    display: none !important;
  }
  html.${HTML_CLASS} .${SETTINGS.panel} .${SETTINGS.content} {
    flex: 1 1 auto !important;
    width: 100% !important;
    min-height: 0 !important;
  }
  html.${HTML_CLASS} .${SETTINGS.panel} .${SETTINGS.options} {
    overflow-y: auto !important;
    -webkit-overflow-scrolling: touch;
  }

  /* Composer font metrics left at DSH default (do not override textarea/mirror). */
  /* Keep ordinary form controls readable without overriding the composer mirror. */
  html.${HTML_CLASS} input:not([type="checkbox"]):not([type="radio"]):not([type="range"]):not([type="file"]) {
    font-size: 14px !important;
  }

  /* ==========================================================================
     IDE Compact Workspace / CodeBuddy-like High Density Styles
     ========================================================================== */

  /* 1. Markdown 代码块 (pre / code) 高密度紧凑优化 */
  html.${HTML_CLASS} pre,
  html.${HTML_CLASS} :where(pre),
  html.${HTML_CLASS} [class*="block_"] :where(pre),
  html.${HTML_CLASS} .md-code-block pre,
  html.${HTML_CLASS} pre[class*="shiki"] {
    font-family: var(--ds-font-family-code, "JetBrains Mono", "SF Mono", Consolas, Menlo, monospace) !important;
    font-size: 12.5px !important;
    line-height: 1.45 !important;
    padding: 10px 12px !important;
    margin: 8px 0 !important;
    border-radius: 8px !important;
    letter-spacing: -0.01em;
  }

  html.${HTML_CLASS} pre code,
  html.${HTML_CLASS} :where(pre) code {
    font-size: 12.5px !important;
    line-height: 1.45 !important;
    font-family: inherit !important;
  }

  /* 2. Markdown 行内代码 (Inline Code) 精致小胶囊 */
  html.${HTML_CLASS} :not(pre) > code,
  html.${HTML_CLASS} p code,
  html.${HTML_CLASS} li code {
    font-family: var(--ds-font-family-code, "JetBrains Mono", "SF Mono", Consolas, Menlo, monospace) !important;
    font-size: 11.5px !important;
    line-height: 1.35 !important;
    padding: 1.5px 5px !important;
    margin: 0 2px !important;
    border-radius: 4px !important;
    background: var(--dsw-alias-markdown-inline-code, rgba(125, 125, 125, 0.12)) !important;
    word-break: break-all;
    vertical-align: baseline;
  }

  /* 3. 文件链接胶囊 / 文件引用 / 产物列表 (File References / Deliverables / File Mentions) 紧凑化 */
  html.${HTML_CLASS} [class*="fileMention"],
  html.${HTML_CLASS} [class*="fileHeader"],
  html.${HTML_CLASS} [class*="filePill"],
  html.${HTML_CLASS} [class*="pill_"],
  html.${HTML_CLASS} [data-produced-files-row] button,
  html.${HTML_CLASS} button[class*="file_"] {
    font-size: 11.5px !important;
    line-height: 16px !important;
    padding: 2px 7px !important;
    height: auto !important;
    min-height: 22px !important;
    border-radius: 5px !important;
    gap: 4px !important;
  }

  /* 文件路径单行省略或自然折行，避免大块臃肿卡片 */
  html.${HTML_CLASS} [class*="filePath"],
  html.${HTML_CLASS} [class*="path_"] {
    font-size: 12px !important;
    line-height: 16px !important;
    font-weight: 500 !important;
  }

  /* 4. 代码块顶部工具条 (语言 / Copy 按钮) 紧凑化 */
  html.${HTML_CLASS} [class*="copyButton_"],
  html.${HTML_CLASS} [class*="action_178r4"],
  html.${HTML_CLASS} [class*="header_178r4"] {
    font-size: 11.5px !important;
    padding: 2px 6px !important;
  }

  /* 5. 消息气泡正文排版与列表间距紧凑化 */
  html.${HTML_CLASS} [class*="markdown_"] {
    font-size: 13.5px !important;
    line-height: 1.55 !important;
  }

  html.${HTML_CLASS} [class*="markdown_"] p {
    margin: 6px 0 !important;
  }

  html.${HTML_CLASS} [class*="markdown_"] ul,
  html.${HTML_CLASS} [class*="markdown_"] ol {
    margin: 4px 0 6px 0 !important;
    padding-left: 20px !important;
  }

  html.${HTML_CLASS} [class*="markdown_"] li {
    margin: 2px 0 !important;
  }

  html.${HTML_CLASS} [class*="markdown_"] h1 {
    font-size: 16px !important;
    margin: 12px 0 6px !important;
  }
  html.${HTML_CLASS} [class*="markdown_"] h2 {
    font-size: 15px !important;
    margin: 10px 0 5px !important;
  }
  html.${HTML_CLASS} [class*="markdown_"] h3,
  html.${HTML_CLASS} [class*="markdown_"] h4 {
    font-size: 14px !important;
    margin: 8px 0 4px !important;
  }
}

.dshMobMenu {
  position: fixed;
  z-index: 60;
  top: calc(2px + env(safe-area-inset-top, 0px));
  left: calc(2px + env(safe-area-inset-left, 0px));
  width: 24px;
  height: 24px;
  border-radius: 6px;
  border: 1px solid var(--dsw-alias-border-l2, rgba(0,0,0,.12));
  background: var(--dsw-alias-button-floating-fill, #fff);
  color: var(--dsw-alias-label-primary, #0f1115);
  box-shadow: none;
  display: none;
  align-items: center;
  justify-content: center;
  padding: 0;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
  touch-action: manipulation;
  user-select: none;
}
.dshMobMenu svg {
  width: 14px;
  height: 14px;
  display: block;
  opacity: 0.55;
}
.dshMobMenu:active {
  background: var(--dsw-alias-button-floating-hover, #f3f4f6);
}
.dshMobMenu[data-flash="true"] {
  border-color: var(--dsw-alias-label-primary, #0f1115);
  opacity: 0.55;
}

.dshMobBackdrop {
  position: fixed;
  inset: 0;
  z-index: 45;
  border: 0;
  margin: 0;
  padding: 0;
  background: rgba(15, 17, 21, 0.4);
  display: none;
  cursor: pointer;
  touch-action: manipulation;
  -webkit-tap-highlight-color: transparent;
}

@media ${MOBILE_MQ} {
  .dshMobMenu[data-visible="true"] {
    display: inline-flex;
  }
  .dshMobBackdrop[data-visible="true"] {
    display: block;
  }
}
`

    function scrubLegacy() {
      if (typeof document === 'undefined') return
      for (const old of document.querySelectorAll('style[data-plugin="dsh-mobile-hanui"]')) old.remove()
      const html = document.documentElement
      html.removeAttribute(ATTR_DETAILS)
      html.removeAttribute('data-dsh-mobile-chrome-menu')
      html.removeAttribute('data-dsh-mobile-overlay')
      for (const el of document.querySelectorAll(
        '[data-dsh-mobile-frame],[data-dsh-mobile-sidebar],[data-dsh-mobile-center],[data-dsh-mobile-details]',
      )) {
        el.removeAttribute('data-dsh-mobile-frame')
        el.removeAttribute('data-dsh-mobile-sidebar')
        el.removeAttribute('data-dsh-mobile-center')
        el.removeAttribute('data-dsh-mobile-details')
      }
    }

    function ensureStyle() {
      if (typeof document === 'undefined') return
      scrubLegacy()
      if (shellDisabled()) {
        document.documentElement.classList.remove(HTML_CLASS)
        return
      }
      const tag = document.createElement('style')
      tag.dataset.plugin = 'dsh-mobile-hanui'
      tag.dataset.pluginCss = STYLE_ID
      tag.textContent = CSS
      document.head.appendChild(tag)
    }

    function findFrame() {
      return (
        document.querySelector(`.${CLS.frame}`) ||
        document.querySelector('[data-shell-overlay]')?.parentElement ||
        null
      )
    }

    function findSidebar(frame) {
      return frame?.querySelector?.(`.${CLS.sidebar}`) || null
    }

    function getLayout(proxy) {
      return proxy?.__raw ?? proxy
    }

    function useMobile() {
      const [mobile, setMobile] = React.useState(
        () => typeof window !== 'undefined' && window.matchMedia(MOBILE_MQ).matches && !shellDisabled(),
      )
      React.useEffect(() => {
        if (shellDisabled()) {
          setMobile(false)
          return
        }
        const mq = window.matchMedia(MOBILE_MQ)
        const update = () => setMobile(mq.matches && !shellDisabled())
        update()
        mq.addEventListener('change', update)
        return () => mq.removeEventListener('change', update)
      }, [])
      return mobile
    }

    // DSH 抽屉 / 侧栏菜单 icon (16x16 viewBox) — 与 dsh 原生 chrome 一致
    const DRAWER_ICON_PATH =
      'M9.67272 0.522841C10.8339 0.522841 11.76 0.522714 12.4963 0.602493C13.2453 0.683657 13.8789 0.854248 14.4264 1.25197C14.7504 1.48739 15.0355 1.77247 15.2709 2.0965C15.6686 2.64394 15.8392 3.27758 15.9204 4.02655C16.0002 4.7629 16 5.68895 16 6.85014V9.14986C16 10.3111 16.0002 11.2371 15.9204 11.9735C15.8392 12.7224 15.6686 13.3561 15.2709 13.9035C15.0355 14.2275 14.7504 14.5126 14.4264 14.748C13.8789 15.1458 13.2453 15.3163 12.4963 15.3975C11.76 15.4773 10.8339 15.4772 9.67272 15.4772H6.3273C5.16611 15.4772 4.24006 15.4773 3.50371 15.3975C2.75474 15.3163 2.1211 15.1458 1.57366 14.748C1.24963 14.5126 0.964549 14.2275 0.729131 13.9035C0.331407 13.3561 0.160817 12.7224 0.0796529 11.9735C-0.000126137 11.2371 1.25338e-09 10.3111 1.25338e-09 9.14986V6.85014C1.25329e-09 5.68895 -0.000126137 4.7629 0.0796529 4.02655C0.160817 3.27758 0.331407 2.64394 0.729131 2.0965C0.964549 1.77247 1.24963 1.48739 1.57366 1.25197C2.1211 0.854248 2.75474 0.683657 3.50371 0.602493C4.24006 0.522714 5.16611 0.522841 6.3273 0.522841H9.67272ZM5.54303 1.88715V14.1118C5.78636 14.1128 6.04709 14.1169 6.3273 14.1169H9.67272C10.8639 14.1169 11.7032 14.1164 12.3493 14.0465C12.9824 13.9779 13.3497 13.8494 13.6268 13.6482C13.8354 13.4966 14.0195 13.3125 14.1711 13.1039C14.3723 12.8268 14.5007 12.4595 14.5693 11.8264C14.6393 11.1803 14.6398 10.341 14.6398 9.14986V6.85014C14.6398 5.65896 14.6393 4.81967 14.5693 4.1736C14.5007 3.54048 14.3723 3.17318 14.1711 2.89609C14.0195 2.68747 13.8354 2.50337 13.6268 2.35179C13.3497 2.1506 12.9824 2.02212 12.3493 1.95353C11.7032 1.88358 10.8639 1.88307 9.67272 1.88307H6.3273C6.04709 1.88307 5.78636 1.8862 5.54303 1.88715ZM4.1828 1.91166C3.99125 1.9216 3.8148 1.93577 3.65076 1.95353C3.01764 2.02212 2.65034 2.1506 2.37325 2.35179C2.16463 2.50337 1.98052 2.68747 1.82895 2.89609C1.62776 3.17318 1.49928 3.54048 1.43069 4.1736C1.36074 4.81967 1.36023 5.65896 1.36023 6.85014V9.14986C1.36023 10.341 1.36074 11.1803 1.43069 11.8264C1.49928 12.4595 1.62776 12.8268 1.82895 13.1039C1.98052 13.3125 2.16463 13.4966 2.37325 13.6482C2.65034 13.8494 3.01764 13.9779 3.65076 14.0465C3.81478 14.0642 3.99127 14.0774 4.1828 14.0873V1.91166Z'

    function IconDrawer() {
      return jsx('svg', {
        viewBox: '0 0 16 16',
        width: 16,
        height: 16,
        fill: 'none',
        'aria-hidden': true,
        children: jsx('path', {
          d: DRAWER_ICON_PATH,
          fill: 'currentColor',
          'fill-rule': 'evenodd',
          'clip-rule': 'evenodd',
        }),
      })
    }

    function classifyStatsGroup(text) {
      const t = String(text || '')
      if ((/轮/.test(t) && /步/.test(t)) || (/turns?/i.test(t) && /steps?/i.test(t))) return 'counts'
      if (/LLM|工具调用|Tool call/i.test(t)) return 'durations'
      if (/TTFT|tok\/s|首 token/i.test(t)) return 'speeds'
      if (/缓存命中|Cache hit/i.test(t)) return 'cacheHit'
      if ((/输入/.test(t) && /输出/.test(t)) || (/Input/i.test(t) && /Output/i.test(t))) return 'tokens'
      return null
    }

    function tagStatsRoot(root) {
      if (!(root instanceof Element)) return
      const groupSpans = Array.from(root.children).filter(
        (el) =>
          el.tagName === 'SPAN' &&
          !el.classList.contains(STATS.sep) &&
          !el.hasAttribute('data-dsh-stats-break'),
      )
      let cacheHitEl = null
      let tokensEl = null
      for (const span of groupSpans) {
        const kind = classifyStatsGroup(span.textContent)
        if (!kind) continue
        if (span.getAttribute('data-dsh-stats') !== kind) {
          span.setAttribute('data-dsh-stats', kind)
        }
        if (kind === 'speeds') {
          const prev = span.previousElementSibling
          const next = span.nextElementSibling
          if (prev?.classList?.contains(STATS.sep) && prev.getAttribute('data-dsh-stats') !== 'sep-hide') {
            prev.setAttribute('data-dsh-stats', 'sep-hide')
          }
          if (next?.classList?.contains(STATS.sep) && next.getAttribute('data-dsh-stats') !== 'sep-hide') {
            next.setAttribute('data-dsh-stats', 'sep-hide')
          }
        } else if (kind === 'cacheHit') {
          cacheHitEl = span
        } else if (kind === 'tokens') {
          tokensEl = span
        }
      }
      const breakTarget = cacheHitEl || tokensEl
      const existingBreaks = Array.from(root.querySelectorAll('[data-dsh-stats-break]'))
      if (!breakTarget) {
        if (existingBreaks.length > 0) {
          for (const b of existingBreaks) b.remove()
        }
        return
      }
      let breakEl = existingBreaks[0] || null
      for (let i = 1; i < existingBreaks.length; i++) existingBreaks[i].remove()
      if (!breakEl) {
        breakEl = document.createElement('span')
        breakEl.setAttribute('data-dsh-stats-break', '')
        breakEl.setAttribute('aria-hidden', 'true')
      }
      if (breakEl.nextElementSibling !== breakTarget) {
        root.insertBefore(breakEl, breakTarget)
      }
    }

    function tagAllStatsRoots() {
      if (!document.documentElement.classList.contains(HTML_CLASS)) return
      for (const root of document.querySelectorAll(`.${STATS.root}`)) {
        tagStatsRoot(root)
      }
    }

    function MobileChrome({ getLayout: getLayoutFn }) {
      const mobile = useMobile()
      const [frame, setFrame] = React.useState(null)
      const [sidebarEl, setSidebarEl] = React.useState(null)
      const [collapsed, setCollapsed] = React.useState(true)
      const [detailsOpen, setDetailsOpen] = React.useState(false)
      const getLayoutRef = React.useRef(getLayoutFn)
      getLayoutRef.current = getLayoutFn
      const ignoreBackdropClickUntil = React.useRef(0)

      const withLayout = React.useCallback((fn) => {
        try {
          const layout = getLayout(getLayoutRef.current?.())
          if (!layout) return
          return fn(layout)
        } catch (err) {
          console.warn('[dsh-mobile-hanui] layout', err)
        }
      }, [])

      const openSidebar = React.useCallback(() => {
        const layout = getLayout(getLayoutRef.current?.())
        if (!layout?.toggleSidebar) {
          console.warn('[dsh-mobile-hanui] toggleSidebar unavailable')
          return
        }
        // The FAB is only rendered while collapsed, so a straight toggle here
        // opens the drawer. Avoid gating on the frame's data-sidebar-collapsed
        // attribute, whose state can lag React and silently no-op the tap.
        layout.toggleSidebar()
      }, [])

      const toggleSidebar = React.useCallback(() => {
        withLayout((layout) => layout.toggleSidebar?.())
      }, [withLayout])

      const closeDetails = React.useCallback(() => {
        withLayout((layout) => layout.closeDetails?.())
        document.documentElement.removeAttribute(ATTR_DETAILS)
        setDetailsOpen(false)
      }, [withLayout])

      const onClose = React.useCallback(() => {
        if (detailsOpen || document.documentElement.hasAttribute(ATTR_DETAILS)) closeDetails()
        else toggleSidebar()
      }, [detailsOpen, closeDetails, toggleSidebar])

      React.useEffect(() => {
        ensureStyle()
        if (!mobile) {
          document.documentElement.classList.remove(HTML_CLASS)
          document.documentElement.removeAttribute(ATTR_DETAILS)
          setFrame(null)
          setSidebarEl(null)
          setDetailsOpen(false)
          return
        }
        document.documentElement.classList.add(HTML_CLASS)
        document.documentElement.removeAttribute(ATTR_DETAILS)

        let raf = 0
        let bodyObs = null
        let alive = true

        const sync = () => {
          if (!alive) return
          const f = findFrame()
          if (!f) return
          const side = findSidebar(f)
          setFrame((prev) => (prev === f ? prev : f))
          setSidebarEl((prev) => (prev === side ? prev : side))
          // Canary after paint: center must occupy the 1fr track
          requestAnimationFrame(() => {
            if (!alive) return
            const center = f.querySelector(`.${CLS.center}`)
            if (
              center &&
              center.clientWidth === 0 &&
              document.documentElement.classList.contains(HTML_CLASS)
            ) {
              console.warn('[dsh-mobile-hanui] center width 0 — grid placement failed')
              document.documentElement.classList.remove(HTML_CLASS)
            }
          })
          bodyObs?.disconnect()
          bodyObs = null
        }

        const schedule = () => {
          if (raf) return
          raf = requestAnimationFrame(() => {
            raf = 0
            sync()
          })
        }

        sync()
        if (!findFrame()) {
          bodyObs = new MutationObserver(schedule)
          bodyObs.observe(document.body, { childList: true, subtree: true })
        }

        return () => {
          alive = false
          if (raf) cancelAnimationFrame(raf)
          bodyObs?.disconnect()
          document.documentElement.classList.remove(HTML_CLASS)
          document.documentElement.removeAttribute(ATTR_DETAILS)
        }
      }, [mobile])

      // Suppress programmatic input focus on mobile: switching sessions fires
      // an el.focus() in the conversation InputBar, which pops the soft keyboard.
      // We only let focus through when the user actually tapped the composer.
      React.useEffect(() => {
        if (!mobile) return
        let userTapAt = 0
        const onPointerDown = (e) => {
          const t = e.target
          if (t instanceof Element && t.closest('textarea, input, [data-input-mirror], [contenteditable="true"]')) {
            userTapAt = Date.now()
          }
        }
        const onFocusIn = (e) => {
          const t = e.target
          if (!(t instanceof Element)) return
          const isComposer = t.closest('textarea, input[type="text"], input:not([type]), [contenteditable="true"]')
          if (!isComposer) return
          // A focus within ~600ms of a real tap is user-intended — allow it.
          if (Date.now() - userTapAt < 600) return
          // Otherwise it is programmatic (session switch) — blur to keep the
          // keyboard closed.
          if (typeof t.blur === 'function') t.blur()
        }
        document.addEventListener('pointerdown', onPointerDown, true)
        document.addEventListener('touchstart', onPointerDown, { capture: true, passive: true })
        document.addEventListener('focusin', onFocusIn, true)
        return () => {
          document.removeEventListener('pointerdown', onPointerDown, true)
          document.removeEventListener('touchstart', onPointerDown, true)
          document.removeEventListener('focusin', onFocusIn, true)
        }
      }, [mobile])

      // Mobile-only infinite scroll: when the reader scrolls the chat column to
      // the top and a "load earlier" page exists, click the (still-rendered)
      // "加载更早" button instead of requiring a manual tap. Desktop is untouched
      // — this lives entirely in the mobile shell plugin.
      React.useEffect(() => {
        if (!mobile) return
        let lastTapAt = 0
        let raf = 0
        const tick = () => {
          raf = 0
          const scroller = document.querySelector(`.${CLS.chatScroll}`) || document.querySelector('[data-conversation-scroll]')
          if (!scroller) return
          const el = scroller.scrollHeight > scroller.clientHeight ? scroller : null
          if (!el) return
          const older = scroller.querySelector(`.${CLS.chatOlder} button`)
          if (!older || older.disabled) return
          if (el.scrollTop <= 40) {
            const now = Date.now()
            if (now - lastTapAt < 500) return
            lastTapAt = now
            older.click()
          }
        }
        const onScroll = (e) => {
          const t = e.target
          if (!(t instanceof Element)) return
          if (t.classList?.contains(CLS.chatScroll) || t.closest?.('[data-conversation-scroll]')) {
            if (!raf) raf = requestAnimationFrame(tick)
          }
        }
        document.addEventListener('scroll', onScroll, { capture: true, passive: true })
        return () => {
          document.removeEventListener('scroll', onScroll, true)
          if (raf) cancelAnimationFrame(raf)
        }
      }, [mobile])

      // StatsLine Option A — tag groups + insert flex break (mobile shell only)
      React.useEffect(() => {
        if (!mobile || shellDisabled()) return
        let raf = 0
        const schedule = () => {
          if (raf) return
          raf = requestAnimationFrame(() => {
            raf = 0
            tagAllStatsRoots()
          })
        }
        schedule()
        const obs = new MutationObserver(schedule)
        // NOTE: observe childList only (new nodes), NOT characterData. Streams
        // mutate text on every token while loading history / generating, which
        // would re-scan all stat roots hundreds of times and slow the phone.
        obs.observe(document.body, { childList: true, subtree: true })
        return () => {
          if (raf) cancelAnimationFrame(raf)
          obs.disconnect()
        }
      }, [mobile])

      React.useEffect(() => {
        if (!mobile) return
        let cancelled = false
        let patched = null
        let tries = 0
        let timer = 0

        const tryPatch = () => {
          if (cancelled) return
          const layout = getLayout(getLayoutRef.current?.())
          if (!layout || typeof layout.openDetails !== 'function' || typeof layout.closeDetails !== 'function') {
            if (tries++ < 40) timer = window.setTimeout(tryPatch, 100)
            return
          }
          if (layout.__dshMobileHanuiPatched) {
            patched = layout
            return
          }

          const origOpen = layout.openDetails.bind(layout)
          const origClose = layout.closeDetails.bind(layout)

          layout.openDetails = (...args) => {
            try {
              origOpen(...args)
              document.documentElement.setAttribute(ATTR_DETAILS, '')
              setDetailsOpen(true)
              const f = findFrame()
              if (f && !f.hasAttribute('data-sidebar-collapsed')) layout.toggleSidebar?.()
            } catch (err) {
              console.warn('[dsh-mobile-hanui] openDetails', err)
              document.documentElement.removeAttribute(ATTR_DETAILS)
              setDetailsOpen(false)
            }
          }

          layout.closeDetails = (...args) => {
            try {
              origClose(...args)
            } catch (err) {
              console.warn('[dsh-mobile-hanui] closeDetails', err)
            }
            document.documentElement.removeAttribute(ATTR_DETAILS)
            setDetailsOpen(false)
          }

          layout.__dshMobileHanuiPatched = true
          layout.__dshMobileHanuiOrigOpen = origOpen
          layout.__dshMobileHanuiOrigClose = origClose
          patched = layout
        }

        tryPatch()

        return () => {
          cancelled = true
          if (timer) window.clearTimeout(timer)
          if (patched?.__dshMobileHanuiPatched) {
            patched.openDetails = patched.__dshMobileHanuiOrigOpen
            patched.closeDetails = patched.__dshMobileHanuiOrigClose
            delete patched.__dshMobileHanuiPatched
            delete patched.__dshMobileHanuiOrigOpen
            delete patched.__dshMobileHanuiOrigClose
          }
          document.documentElement.removeAttribute(ATTR_DETAILS)
          setDetailsOpen(false)
        }
      }, [mobile])

      React.useEffect(() => {
        if (!frame) return
        const read = () => setCollapsed(frame.hasAttribute('data-sidebar-collapsed'))
        read()
        const obs = new MutationObserver(read)
        obs.observe(frame, { attributes: true, attributeFilter: ['data-sidebar-collapsed'] })
        return () => obs.disconnect()
      }, [frame])

      // Swipe-left on visible backdrop → same onClose as backdrop click. No swipe-open.
      React.useEffect(() => {
        if (!mobile) return
        let startX = 0
        let startY = 0
        let mode = null // 'close' | null
        let aborted = false

        const onStart = (e) => {
          const backdrop = document.querySelector('.dshMobBackdrop[data-visible="true"]')
          if (!backdrop) {
            mode = null
            return
          }
          const t = e.touches?.[0]
          if (!t) return
          const target = e.target
          const onBackdrop =
            target === backdrop || (target instanceof Element && backdrop.contains(target))
          if (!onBackdrop) {
            mode = null
            return
          }
          startX = t.clientX
          startY = t.clientY
          aborted = false
          mode = 'close'
        }

        const onMove = (e) => {
          if (!mode || aborted) return
          const t = e.touches?.[0]
          if (!t) return
          const dx = t.clientX - startX
          const dy = t.clientY - startY
          if (Math.abs(dx) < SWIPE_AXIS_LOCK && Math.abs(dy) < SWIPE_AXIS_LOCK) return
          if (Math.abs(dy) > Math.abs(dx)) {
            aborted = true
            mode = null
          }
        }

        const onEnd = (e) => {
          if (!mode || aborted) {
            mode = null
            aborted = false
            return
          }
          mode = null
          const t = e.changedTouches?.[0]
          if (!t) return
          const dx = t.clientX - startX
          const dy = t.clientY - startY
          if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
            ignoreBackdropClickUntil.current = Date.now() + 300
          }
          if (dx > -SWIPE_DX_MIN || Math.abs(dx) < Math.abs(dy) * SWIPE_DX_DY) return
          ignoreBackdropClickUntil.current = Date.now() + 300
          onClose()
        }

        window.addEventListener('touchstart', onStart, { passive: true })
        window.addEventListener('touchmove', onMove, { passive: true })
        window.addEventListener('touchend', onEnd, { passive: true })
        window.addEventListener('touchcancel', onEnd, { passive: true })
        return () => {
          window.removeEventListener('touchstart', onStart)
          window.removeEventListener('touchmove', onMove)
          window.removeEventListener('touchend', onEnd)
          window.removeEventListener('touchcancel', onEnd)
        }
      }, [mobile, onClose])

      React.useEffect(() => {
        if (!mobile || !sidebarEl || !frame) return
        const onClick = (e) => {
          if (frame.hasAttribute('data-sidebar-collapsed')) return
          const t = e.target
          if (!(t instanceof Element)) return
          // Row action menus / folder chrome — don't close
          if (t.closest(`.${SIDEBAR_ROW.rowActions}, .${SIDEBAR_ROW.iconButton}, input, textarea, select`)) {
            return
          }
          // Workspace section-header controls (search view, view options, add
          // workspace) open sub-views / menus inside the drawer — keep it open.
          if (t.closest(`.${WS_HDR.sectionHeader}, .${WS_HDR.search}, .${WS_HDR.searchButton}, .${WS_HDR.searchInput}, .${WS_HDR.clearButton}, .${WS_HDR.iconButton}`)) {
            return
          }
          // Session rows are div[role=treeitem].YDXeBa_sessionRow (not <button>)
          const sessionHit = t.closest(`.${SIDEBAR_ROW.session}, .${SIDEBAR_ROW.search}`)
          if (sessionHit) {
            const closeIfOpen = () => {
              if (!frame.hasAttribute('data-sidebar-collapsed')) toggleSidebar()
            }
            window.setTimeout(closeIfOpen, 140)
            window.setTimeout(closeIfOpen, 280)
            return
          }
          // Other nav (new chat, settings, …) — skip project/folder expand rows
          if (t.closest(`.${SIDEBAR_ROW.project}`)) return
          // The settings trigger opens a full-screen overlay that lives INSIDE
          // the drawer; closing the drawer here would kill the just-opened panel
          // (the "settings flashes then disappears" bug). Keep the drawer open.
          if (t.closest(`.${SETTINGS.overlay}, .${SETTINGS.trigger}`)) return
          const actionable = t.closest(
            'button, a, [role="button"], [role="option"], [role="menuitem"], li, [data-session-id], [data-conversation-id]',
          )
          if (!actionable) return
          const closeIfOpen = () => {
            if (!frame.hasAttribute('data-sidebar-collapsed')) toggleSidebar()
          }
          window.setTimeout(closeIfOpen, 140)
          window.setTimeout(closeIfOpen, 280)
        }
        sidebarEl.addEventListener('click', onClick, true)
        return () => sidebarEl.removeEventListener('click', onClick, true)
      }, [mobile, sidebarEl, frame, toggleSidebar])

      React.useEffect(() => {
        if (!mobile) return
        const onKey = (e) => {
          if (e.key !== 'Escape') return
          if (document.documentElement.hasAttribute(ATTR_DETAILS)) {
            closeDetails()
            return
          }
          if (frame && !frame.hasAttribute('data-sidebar-collapsed')) toggleSidebar()
        }
        window.addEventListener('keydown', onKey)
        return () => window.removeEventListener('keydown', onKey)
      }, [mobile, frame, toggleSidebar, closeDetails])

      if (!mobile) return null

      const sidebarOpen = !!frame && !collapsed
      const showMenu = !!frame && collapsed && !detailsOpen
      const showBackdrop = sidebarOpen || detailsOpen

      const onBackdropClick = () => {
        if (Date.now() < ignoreBackdropClickUntil.current) return
        onClose()
      }

      return jsxs(React.Fragment, {
        children: [
          jsx('button', {
            type: 'button',
            className: 'dshMobMenu',
            'data-visible': showMenu ? 'true' : 'false',
            'aria-label': '打开菜单',
            onClick: openSidebar,
            children: jsx(IconDrawer, {}),
          }),
          jsx('button', {
            type: 'button',
            className: 'dshMobBackdrop',
            'data-visible': showBackdrop ? 'true' : 'false',
            'aria-label': '关闭面板',
            onClick: onBackdropClick,
          }),
        ],
      })
    }

    const inject = ['slots', 'layout']

    function apply(ctx) {
      ensureStyle()
      ctx.effect(
        () =>
          ctx.slots.inject('shell.overlay', () =>
            ctx.slots.register(
              {
                name: 'shell.overlay',
                id: 'dsh-mobile-hanui-chrome',
                order: 10,
                label: 'Mobile chrome',
              },
              () =>
                jsx(MobileChrome, {
                  getLayout: () => ctx.layout,
                }),
            ),
          ),
        'dsh-mobile-hanui: shell.overlay',
      )
    }

    exports.apply = apply
    exports.inject = inject
    return module.exports
  },
})

