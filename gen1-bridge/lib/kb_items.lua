-- kb_items: the canonical item catalogue (protocol/items.json, docs/specs/
-- MULTI_GAME_PLAN.md §4.9): each item of the one trainer economy with its
-- class, the move a machine teaches, and each family's symbol for it.
--
-- GENERATED from protocol/items.json by tools/kb_protocol/catalog.py (--write);
-- never edit by hand. A test fails when it is stale.

return {
  items = {
    antidote = {
      class = "medicine",
      cobblemon = "antidote",
      symbols = {
        gen1 = "ANTIDOTE",
      },
    },
    awakening = {
      class = "medicine",
      cobblemon = "awakening",
      symbols = {
        gen1 = "AWAKENING",
      },
    },
    bicycle = {
      class = "key",
      symbols = {
        gen1 = "BICYCLE",
      },
    },
    bike_voucher = {
      class = "key",
      symbols = {
        gen1 = "BIKE_VOUCHER",
      },
    },
    burn_heal = {
      class = "medicine",
      cobblemon = "burn_heal",
      symbols = {
        gen1 = "BURN_HEAL",
      },
    },
    calcium = {
      class = "medicine",
      symbols = {
        gen1 = "CALCIUM",
      },
    },
    carbos = {
      class = "medicine",
      symbols = {
        gen1 = "CARBOS",
      },
    },
    card_key = {
      class = "key",
      symbols = {
        gen1 = "CARD_KEY",
      },
    },
    coin = {
      class = "key",
      symbols = {
        gen1 = "COIN",
      },
    },
    coin_case = {
      class = "key",
      symbols = {
        gen1 = "COIN_CASE",
      },
    },
    dire_hit = {
      class = "battle",
      cobblemon = "dire_hit",
      symbols = {
        gen1 = "DIRE_HIT",
      },
    },
    dome_fossil = {
      class = "key",
      symbols = {
        gen1 = "DOME_FOSSIL",
      },
    },
    elixer = {
      class = "medicine",
      cobblemon = "elixir",
      symbols = {
        gen1 = "ELIXER",
      },
    },
    escape_rope = {
      class = "field",
      symbols = {
        gen1 = "ESCAPE_ROPE",
      },
    },
    ether = {
      class = "medicine",
      cobblemon = "ether",
      symbols = {
        gen1 = "ETHER",
      },
    },
    exp_all = {
      class = "key",
      symbols = {
        gen1 = "EXP_ALL",
      },
    },
    fire_stone = {
      class = "stone",
      cobblemon = "fire_stone",
      symbols = {
        gen1 = "FIRE_STONE",
      },
    },
    fresh_water = {
      class = "medicine",
      symbols = {
        gen1 = "FRESH_WATER",
      },
    },
    full_heal = {
      class = "medicine",
      cobblemon = "full_heal",
      symbols = {
        gen1 = "FULL_HEAL",
      },
    },
    full_restore = {
      class = "medicine",
      cobblemon = "full_restore",
      symbols = {
        gen1 = "FULL_RESTORE",
      },
    },
    gold_teeth = {
      class = "key",
      symbols = {
        gen1 = "GOLD_TEETH",
      },
    },
    good_rod = {
      class = "key",
      symbols = {
        gen1 = "GOOD_ROD",
      },
    },
    great_ball = {
      class = "ball",
      cobblemon = "great_ball",
      symbols = {
        gen1 = "GREAT_BALL",
      },
    },
    guard_spec = {
      class = "battle",
      cobblemon = "guard_spec",
      symbols = {
        gen1 = "GUARD_SPEC",
      },
    },
    helix_fossil = {
      class = "key",
      symbols = {
        gen1 = "HELIX_FOSSIL",
      },
    },
    hm_cut = {
      class = "key",
      move = 15,
      symbols = {
        gen1 = "HM_CUT",
      },
    },
    hm_flash = {
      class = "key",
      move = 148,
      symbols = {
        gen1 = "HM_FLASH",
      },
    },
    hm_fly = {
      class = "key",
      move = 19,
      symbols = {
        gen1 = "HM_FLY",
      },
    },
    hm_strength = {
      class = "key",
      move = 70,
      symbols = {
        gen1 = "HM_STRENGTH",
      },
    },
    hm_surf = {
      class = "key",
      move = 57,
      symbols = {
        gen1 = "HM_SURF",
      },
    },
    hp_up = {
      class = "medicine",
      symbols = {
        gen1 = "HP_UP",
      },
    },
    hyper_potion = {
      class = "medicine",
      cobblemon = "hyper_potion",
      symbols = {
        gen1 = "HYPER_POTION",
      },
    },
    ice_heal = {
      class = "medicine",
      cobblemon = "ice_heal",
      symbols = {
        gen1 = "ICE_HEAL",
      },
    },
    iron = {
      class = "medicine",
      symbols = {
        gen1 = "IRON",
      },
    },
    itemfinder = {
      class = "key",
      symbols = {
        gen1 = "ITEMFINDER",
      },
    },
    leaf_stone = {
      class = "stone",
      cobblemon = "leaf_stone",
      symbols = {
        gen1 = "LEAF_STONE",
      },
    },
    lemonade = {
      class = "medicine",
      symbols = {
        gen1 = "LEMONADE",
      },
    },
    lift_key = {
      class = "key",
      symbols = {
        gen1 = "LIFT_KEY",
      },
    },
    master_ball = {
      class = "ball",
      cobblemon = "master_ball",
      symbols = {
        gen1 = "MASTER_BALL",
      },
    },
    max_elixer = {
      class = "medicine",
      cobblemon = "max_elixir",
      symbols = {
        gen1 = "MAX_ELIXER",
      },
    },
    max_ether = {
      class = "medicine",
      cobblemon = "max_ether",
      symbols = {
        gen1 = "MAX_ETHER",
      },
    },
    max_potion = {
      class = "medicine",
      cobblemon = "max_potion",
      symbols = {
        gen1 = "MAX_POTION",
      },
    },
    max_repel = {
      class = "field",
      symbols = {
        gen1 = "MAX_REPEL",
      },
    },
    max_revive = {
      class = "medicine",
      cobblemon = "max_revive",
      symbols = {
        gen1 = "MAX_REVIVE",
      },
    },
    moon_stone = {
      class = "stone",
      cobblemon = "moon_stone",
      symbols = {
        gen1 = "MOON_STONE",
      },
    },
    nugget = {
      class = "valuable",
      symbols = {
        gen1 = "NUGGET",
      },
    },
    oaks_parcel = {
      class = "key",
      symbols = {
        gen1 = "OAKS_PARCEL",
      },
    },
    old_amber = {
      class = "key",
      symbols = {
        gen1 = "OLD_AMBER",
      },
    },
    old_rod = {
      class = "key",
      symbols = {
        gen1 = "OLD_ROD",
      },
    },
    parlyz_heal = {
      class = "medicine",
      cobblemon = "paralyze_heal",
      symbols = {
        gen1 = "PARLYZ_HEAL",
      },
    },
    poke_ball = {
      class = "ball",
      cobblemon = "poke_ball",
      symbols = {
        gen1 = "POKE_BALL",
      },
    },
    poke_doll = {
      class = "field",
      symbols = {
        gen1 = "POKE_DOLL",
      },
    },
    poke_flute = {
      class = "key",
      symbols = {
        gen1 = "POKE_FLUTE",
      },
    },
    pokedex = {
      class = "key",
      symbols = {
        gen1 = "POKEDEX",
      },
    },
    potion = {
      class = "medicine",
      cobblemon = "potion",
      symbols = {
        gen1 = "POTION",
      },
    },
    pp_up = {
      class = "medicine",
      symbols = {
        gen1 = "PP_UP",
      },
    },
    protein = {
      class = "medicine",
      symbols = {
        gen1 = "PROTEIN",
      },
    },
    rare_candy = {
      class = "medicine",
      symbols = {
        gen1 = "RARE_CANDY",
      },
    },
    repel = {
      class = "field",
      symbols = {
        gen1 = "REPEL",
      },
    },
    revive = {
      class = "medicine",
      cobblemon = "revive",
      symbols = {
        gen1 = "REVIVE",
      },
    },
    s_s_ticket = {
      class = "key",
      symbols = {
        gen1 = "S_S_TICKET",
      },
    },
    safari_ball = {
      class = "ball",
      cobblemon = "safari_ball",
      symbols = {
        gen1 = "SAFARI_BALL",
      },
    },
    secret_key = {
      class = "key",
      symbols = {
        gen1 = "SECRET_KEY",
      },
    },
    silph_scope = {
      class = "key",
      symbols = {
        gen1 = "SILPH_SCOPE",
      },
    },
    soda_pop = {
      class = "medicine",
      symbols = {
        gen1 = "SODA_POP",
      },
    },
    super_potion = {
      class = "medicine",
      cobblemon = "super_potion",
      symbols = {
        gen1 = "SUPER_POTION",
      },
    },
    super_repel = {
      class = "field",
      symbols = {
        gen1 = "SUPER_REPEL",
      },
    },
    super_rod = {
      class = "key",
      symbols = {
        gen1 = "SUPER_ROD",
      },
    },
    surfboard = {
      class = "key",
      symbols = {
        gen1 = "SURFBOARD",
      },
    },
    thunder_stone = {
      class = "stone",
      cobblemon = "thunder_stone",
      symbols = {
        gen1 = "THUNDER_STONE",
      },
    },
    tm_bide = {
      class = "tm",
      move = 117,
      symbols = {
        gen1 = "TM_BIDE",
      },
    },
    tm_blizzard = {
      class = "tm",
      move = 59,
      symbols = {
        gen1 = "TM_BLIZZARD",
      },
    },
    tm_body_slam = {
      class = "tm",
      move = 34,
      symbols = {
        gen1 = "TM_BODY_SLAM",
      },
    },
    tm_bubblebeam = {
      class = "tm",
      move = 61,
      symbols = {
        gen1 = "TM_BUBBLEBEAM",
      },
    },
    tm_counter = {
      class = "tm",
      move = 68,
      symbols = {
        gen1 = "TM_COUNTER",
      },
    },
    tm_dig = {
      class = "tm",
      move = 91,
      symbols = {
        gen1 = "TM_DIG",
      },
    },
    tm_double_edge = {
      class = "tm",
      move = 38,
      symbols = {
        gen1 = "TM_DOUBLE_EDGE",
      },
    },
    tm_double_team = {
      class = "tm",
      move = 104,
      symbols = {
        gen1 = "TM_DOUBLE_TEAM",
      },
    },
    tm_dragon_rage = {
      class = "tm",
      move = 82,
      symbols = {
        gen1 = "TM_DRAGON_RAGE",
      },
    },
    tm_dream_eater = {
      class = "tm",
      move = 138,
      symbols = {
        gen1 = "TM_DREAM_EATER",
      },
    },
    tm_earthquake = {
      class = "tm",
      move = 89,
      symbols = {
        gen1 = "TM_EARTHQUAKE",
      },
    },
    tm_egg_bomb = {
      class = "tm",
      move = 121,
      symbols = {
        gen1 = "TM_EGG_BOMB",
      },
    },
    tm_explosion = {
      class = "tm",
      move = 153,
      symbols = {
        gen1 = "TM_EXPLOSION",
      },
    },
    tm_fire_blast = {
      class = "tm",
      move = 126,
      symbols = {
        gen1 = "TM_FIRE_BLAST",
      },
    },
    tm_fissure = {
      class = "tm",
      move = 90,
      symbols = {
        gen1 = "TM_FISSURE",
      },
    },
    tm_horn_drill = {
      class = "tm",
      move = 32,
      symbols = {
        gen1 = "TM_HORN_DRILL",
      },
    },
    tm_hyper_beam = {
      class = "tm",
      move = 63,
      symbols = {
        gen1 = "TM_HYPER_BEAM",
      },
    },
    tm_ice_beam = {
      class = "tm",
      move = 58,
      symbols = {
        gen1 = "TM_ICE_BEAM",
      },
    },
    tm_mega_drain = {
      class = "tm",
      move = 72,
      symbols = {
        gen1 = "TM_MEGA_DRAIN",
      },
    },
    tm_mega_kick = {
      class = "tm",
      move = 25,
      symbols = {
        gen1 = "TM_MEGA_KICK",
      },
    },
    tm_mega_punch = {
      class = "tm",
      move = 5,
      symbols = {
        gen1 = "TM_MEGA_PUNCH",
      },
    },
    tm_metronome = {
      class = "tm",
      move = 118,
      symbols = {
        gen1 = "TM_METRONOME",
      },
    },
    tm_mimic = {
      class = "tm",
      move = 102,
      symbols = {
        gen1 = "TM_MIMIC",
      },
    },
    tm_pay_day = {
      class = "tm",
      move = 6,
      symbols = {
        gen1 = "TM_PAY_DAY",
      },
    },
    tm_psychic_m = {
      class = "tm",
      move = 94,
      symbols = {
        gen1 = "TM_PSYCHIC_M",
      },
    },
    tm_psywave = {
      class = "tm",
      move = 149,
      symbols = {
        gen1 = "TM_PSYWAVE",
      },
    },
    tm_rage = {
      class = "tm",
      move = 99,
      symbols = {
        gen1 = "TM_RAGE",
      },
    },
    tm_razor_wind = {
      class = "tm",
      move = 13,
      symbols = {
        gen1 = "TM_RAZOR_WIND",
      },
    },
    tm_reflect = {
      class = "tm",
      move = 115,
      symbols = {
        gen1 = "TM_REFLECT",
      },
    },
    tm_rest = {
      class = "tm",
      move = 156,
      symbols = {
        gen1 = "TM_REST",
      },
    },
    tm_rock_slide = {
      class = "tm",
      move = 157,
      symbols = {
        gen1 = "TM_ROCK_SLIDE",
      },
    },
    tm_seismic_toss = {
      class = "tm",
      move = 69,
      symbols = {
        gen1 = "TM_SEISMIC_TOSS",
      },
    },
    tm_selfdestruct = {
      class = "tm",
      move = 120,
      symbols = {
        gen1 = "TM_SELFDESTRUCT",
      },
    },
    tm_skull_bash = {
      class = "tm",
      move = 130,
      symbols = {
        gen1 = "TM_SKULL_BASH",
      },
    },
    tm_sky_attack = {
      class = "tm",
      move = 143,
      symbols = {
        gen1 = "TM_SKY_ATTACK",
      },
    },
    tm_softboiled = {
      class = "tm",
      move = 135,
      symbols = {
        gen1 = "TM_SOFTBOILED",
      },
    },
    tm_solarbeam = {
      class = "tm",
      move = 76,
      symbols = {
        gen1 = "TM_SOLARBEAM",
      },
    },
    tm_submission = {
      class = "tm",
      move = 66,
      symbols = {
        gen1 = "TM_SUBMISSION",
      },
    },
    tm_substitute = {
      class = "tm",
      move = 164,
      symbols = {
        gen1 = "TM_SUBSTITUTE",
      },
    },
    tm_swift = {
      class = "tm",
      move = 129,
      symbols = {
        gen1 = "TM_SWIFT",
      },
    },
    tm_swords_dance = {
      class = "tm",
      move = 14,
      symbols = {
        gen1 = "TM_SWORDS_DANCE",
      },
    },
    tm_take_down = {
      class = "tm",
      move = 36,
      symbols = {
        gen1 = "TM_TAKE_DOWN",
      },
    },
    tm_teleport = {
      class = "tm",
      move = 100,
      symbols = {
        gen1 = "TM_TELEPORT",
      },
    },
    tm_thunder = {
      class = "tm",
      move = 87,
      symbols = {
        gen1 = "TM_THUNDER",
      },
    },
    tm_thunder_wave = {
      class = "tm",
      move = 86,
      symbols = {
        gen1 = "TM_THUNDER_WAVE",
      },
    },
    tm_thunderbolt = {
      class = "tm",
      move = 85,
      symbols = {
        gen1 = "TM_THUNDERBOLT",
      },
    },
    tm_toxic = {
      class = "tm",
      move = 92,
      symbols = {
        gen1 = "TM_TOXIC",
      },
    },
    tm_tri_attack = {
      class = "tm",
      move = 161,
      symbols = {
        gen1 = "TM_TRI_ATTACK",
      },
    },
    tm_water_gun = {
      class = "tm",
      move = 55,
      symbols = {
        gen1 = "TM_WATER_GUN",
      },
    },
    tm_whirlwind = {
      class = "tm",
      move = 18,
      symbols = {
        gen1 = "TM_WHIRLWIND",
      },
    },
    town_map = {
      class = "key",
      symbols = {
        gen1 = "TOWN_MAP",
      },
    },
    ultra_ball = {
      class = "ball",
      cobblemon = "ultra_ball",
      symbols = {
        gen1 = "ULTRA_BALL",
      },
    },
    water_stone = {
      class = "stone",
      cobblemon = "water_stone",
      symbols = {
        gen1 = "WATER_STONE",
      },
    },
    x_accuracy = {
      class = "battle",
      cobblemon = "x_accuracy",
      symbols = {
        gen1 = "X_ACCURACY",
      },
    },
    x_attack = {
      class = "battle",
      cobblemon = "x_attack",
      symbols = {
        gen1 = "X_ATTACK",
      },
    },
    x_defend = {
      class = "battle",
      cobblemon = "x_defence",
      symbols = {
        gen1 = "X_DEFEND",
      },
    },
    x_special = {
      class = "battle",
      cobblemon = "x_special_attack",
      symbols = {
        gen1 = "X_SPECIAL",
      },
    },
    x_speed = {
      class = "battle",
      cobblemon = "x_speed",
      symbols = {
        gen1 = "X_SPEED",
      },
    },
  },
  version = 1,
}
