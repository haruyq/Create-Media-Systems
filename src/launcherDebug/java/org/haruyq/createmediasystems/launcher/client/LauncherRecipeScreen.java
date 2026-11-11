package org.haruyq.createmediasystems.launcher.client;

import org.haruyq.createmediasystems.power.BatteryItem;
import org.haruyq.createmediasystems.launcher.DiscCellCharger;
import com.google.gson.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.haruyq.createmediasystems.launcher.DiscCellTier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Native game renders of the real recipe definitions; excluded from release jars. */
final class LauncherRecipeScreen extends Screen {
    private record Recipe(String id,String name,String source,boolean shapeless,int level,List<String> slots) {}
    private final int page;
    private final List<Recipe> recipes=new ArrayList<>();
    private static final Map<String,String> NAMES=Map.ofEntries(
        Map.entry("create:large_cogwheel","大きな歯車"),Map.entry("create:precision_mechanism","精密機構"),
        Map.entry("create:brass_sheet","真鍮板"),Map.entry("create:copper_sheet","銅板"),
        Map.entry("create:brass_casing","真鍮のケーシング"),Map.entry("create:shaft","シャフト"),
        Map.entry("create:andesite_alloy","安山岩合金"),Map.entry("minecraft:crossbow","クロスボウ"),
        Map.entry("minecraft:quartz","ネザークォーツ"),Map.entry("minecraft:glass","ガラス"),
        Map.entry("minecraft:redstone_block","レッドストーンブロック"),Map.entry("minecraft:diamond","ダイヤモンド"),
        Map.entry("minecraft:amethyst_shard","アメジストの欠片"),Map.entry("minecraft:netherite_ingot","ネザライトインゴット"),
        Map.entry("minecraft:blaze_rod","ブレイズロッド"),Map.entry("minecraft:nether_star","ネザースター"),
        Map.entry("minecraft:echo_shard","残響の欠片"),Map.entry("minecraft:ender_eye","エンダーアイ"),
        Map.entry("minecraft:iron_ingot","鉄インゴット"),Map.entry("minecraft:copper_ingot","銅インゴット"),
        Map.entry("minecraft:copper_block","銅ブロック"),Map.entry("minecraft:daylight_detector","日照センサー"),
        Map.entry("create_media_systems:speaker","スピーカー"),Map.entry("create:electron_tube","電子管"),
        Map.entry("minecraft:white_dye","白色の染料"),Map.entry("minecraft:black_dye","黒色の染料"),
        Map.entry("create_heavy_indusstry:mox_pellet","MOXペレット"),
        Map.entry("create_heavy_indusstry:tungsten_divertor_cassette","タングステンダイバータ"),
        Map.entry("create_heavy_indusstry:titanium_sheet","チタン板"),
        Map.entry("create_heavy_indusstry:zircaloy_cladding_tube","ジルカロイ被覆管"),
        Map.entry("create_heavy_indusstry:fusion_toroidal_field_coil","核融合トロイダル磁場コイル")
    );
    private static final String[] CELLS={"銅ディスクセル","真鍮ディスクセル","ダイヤモンドディスクセル","ネザライトディスクセル","共鳴ディスクセル","ケミ・アペックスディスクセル"};
    LauncherRecipeScreen(int page) {
        super(Component.literal("今回追加したレシピ"));this.page=Math.clamp(page,0,4);
        recipes.add(shaped("disc_launcher","ディスクランチャー",0));
        recipes.add(shaped("disc_cell_charger","セル充電器",0));
        recipes.add(shaped("copper_disc_cell",CELLS[0],1));
        for(var target:DiscCellTier.values())if(target.previous()!=null) {
            int level=target.level;var slots=new ArrayList<String>();
            slots.add("create_media_systems:"+target.previous().itemId());slots.addAll(target.upgradeMaterials());
            recipes.add(new Recipe(target.itemId(),CELLS[level-1],"DiscCellTier.upgradeMaterials",true,level,List.copyOf(slots)));
        }
        recipes.add(shaped("partybox_stage_320","SBL PartyBox Stage 320",0));
        recipes.add(shaped("partybox_battery","PartyBox 交換式バッテリー",0));
        recipes.add(shaped("megaphone_white","SBL メガホン30・ホワイト",0));
        recipes.add(shaped("megaphone_black","SBL メガホン30・ブラック",0));
    }
    private static Recipe shaped(String id,String name,int level) {
        String path="/data/create_media_systems/recipe/"+id+".json";
        try(var in=LauncherRecipeScreen.class.getResourceAsStream(path)) {
            var json=JsonParser.parseReader(new InputStreamReader(Objects.requireNonNull(in),StandardCharsets.UTF_8)).getAsJsonObject();
            List<String> slots=new ArrayList<>();var key=json.getAsJsonObject("key");
            for(var row:json.getAsJsonArray("pattern"))for(char c:row.getAsString().toCharArray())
                slots.add(c==' '?"":key.getAsJsonObject(String.valueOf(c)).get("item").getAsString());
            return new Recipe(id,name,path,false,level,List.copyOf(slots));
        } catch(Exception error) {throw new IllegalStateException("Cannot read actual recipe "+id,error);}
    }
    @Override protected void init() {
        try {Files.writeString(minecraft.gameDirectory.toPath().resolve("recipe-guide.json"),new GsonBuilder().setPrettyPrinting().create().toJson(recipes),StandardCharsets.UTF_8);}
        catch(Exception error) {throw new IllegalStateException(error);}
    }
    private String name(String id) {
        for(var tier:DiscCellTier.values())if(id.equals("create_media_systems:"+tier.itemId()))return "Lv."+tier.level+" "+CELLS[tier.level-1];
        return NAMES.getOrDefault(id,id.substring(id.indexOf(':')+1));
    }
    private void text(GuiGraphics g,String s,int x,int y,float scale,int color) {
        g.pose().pushPose();g.pose().translate(x,y,0);g.pose().scale(scale,scale,1);g.drawString(font,s,0,0,color,false);g.pose().popPose();
    }
    /** The other mod's materials are marked A, B, C... in DiscCellTier.plantItems() order. */
    private static String plantLetter(String id) { return String.valueOf((char)('A'+DiscCellTier.plantItems().indexOf(id))); }
    private void item(GuiGraphics g,String id,int x,int y,float scale) {
        var key=ResourceLocation.parse(id);
        if(!BuiltInRegistries.ITEM.containsKey(key)) {
            String letter=plantLetter(id);
            text(g,letter,x+7,y+4,3,0xFFB9FF64);return;
        }
        g.pose().pushPose();g.pose().translate(x,y,0);g.pose().scale(scale,scale,1);
        g.renderItem(new ItemStack(BuiltInRegistries.ITEM.get(key)),0,0);g.pose().popPose();
    }
    private void card(GuiGraphics g,Recipe r,int x,int y,int w,int h) {
        int accent=r.level==0?0xFF66DDE5:0xFF000000|DiscCellTier.at(r.level).color;
        g.fill(x,y,x+w,y+h,0xFF17242D);g.fill(x,y,x+w,y+3,accent);
        String title=(r.level>0?"Lv."+r.level+"  ":"")+r.name;
        text(g,title,x+18,y+23,Math.min(2f,(w-36f)/font.width(title)),0xFFF0F7F9);
        text(g,r.shapeless?"作業台 / 配置自由（この並べ方でも作成可）":"作業台 / 配置固定",x+18,y+57,1.3f,0xFF9EB4C1);
        int gridX=x+24,gridY=y+103,step=48;
        for(int n=0;n<9;n++) {
            int sx=gridX+n%3*step,sy=gridY+n/3*step;
            g.fill(sx,sy,sx+45,sy+45,0xFF0C141C);g.fill(sx+1,sy+1,sx+44,sy+2,0xFF334955);
            if(!r.slots.get(n).isEmpty())item(g,r.slots.get(n),sx+3,sy+3,2.4f);
        }
        int outX=x+w-107,outY=gridY+35;
        int arrowX=gridX+153,arrowEnd=outX-12;
        g.fill(arrowX,gridY+67,arrowEnd,gridY+71,accent);
        g.fill(arrowEnd-4,gridY+63,arrowEnd,gridY+75,accent);
        text(g,">",arrowEnd-1,gridY+60,2,accent);
        g.fill(outX-6,outY-6,outX+77,outY+77,0xFF0C141C);
        item(g,"create_media_systems:"+r.id,outX,outY,4);
        text(g,"×1",outX+51,outY+80,1.7f,accent);
        text(g,"必要素材",x+21,y+282,1.7f,accent);
        Map<String,Integer> counts=new LinkedHashMap<>();r.slots.stream().filter(id->!id.isEmpty()).forEach(id->counts.merge(id,1,Integer::sum));
        int line=y+315;
        for(var entry:counts.entrySet()) {
            String id=entry.getKey();String label=name(id)+" ×"+entry.getValue();
            if(id.startsWith(DiscCellTier.PLANT_MOD_ID+":")) {
                String letter=plantLetter(id);
                label=letter+"  "+label;
            }
            float scale=Math.min(1.7f,(w-47f)/font.width(label));
            text(g,label,x+24,line,scale,0xFFD4E2E9);line+=26;
            if(id.startsWith(DiscCellTier.PLANT_MOD_ID+":")) {
                text(g,id.substring(id.indexOf(':')+1),x+25,line-7,Math.min(1f,(w-45f)/font.width(id.substring(id.indexOf(':')+1))),0xFF7D959E);
                line+=6;
            }
        }
        int noteY=y+h-78;
        if(r.level>0) {
            var tier=DiscCellTier.at(r.level);
            text(g,"満充電 "+tier.shots+"発  /  ダメージ "+(int)tier.damage,x+21,noteY,1.6f,accent);
            text(g,String.format(Locale.ROOT,"%,d FE  /  1発 %,d FE",tier.capacity(),tier.energyPerShot),x+21,noteY+24,1.35f,0xFF9EB4C1);
        } else if(r.id.equals("partybox_battery")) {
            text(g,String.format(Locale.ROOT,"%,d FE / 68 Wh",BatteryItem.CAPACITY),x+21,noteY,1.8f,accent);
            text(g,"作成直後は空。セル充電器で充電。",x+21,noteY+25,1.5f,0xFF9EB4C1);
        } else if(r.id.equals("disc_cell_charger")) {
            text(g,"Create 回転軸（下面）/ FE 入力",x+21,noteY,1.7f,accent);
            text(g,DiscCellCharger.MIN_RPM+" RPM以上 / セル・PartyBox電池に対応",x+21,noteY+25,1.4f,0xFF9EB4C1);
        } else if(r.id.equals("partybox_stage_320")) {
            text(g,"ディスク＋充電済み電池を装着して再生",x+21,noteY,1.5f,accent);
            text(g,"スピーカーは既存アイテムを使用",x+21,noteY+25,1.4f,0xFF9EB4C1);
        } else if(r.id.startsWith("megaphone_")) {
            text(g,"ディスク＋充電済み電池 / 手に持って再生",x+21,noteY,1.5f,accent);
            text(g,"専用ホーンDSP・前方指向性 / 追加Mod不要",x+21,noteY+25,1.35f,0xFF9EB4C1);
        } else {
            text(g,"装着セルのレベルで弾数・ダメージが変化",x+21,noteY,1.5f,accent);
            text(g,"左クリック：射撃 / 右：照準 / R：セル交換",x+21,noteY+25,1.4f,0xFF9EB4C1);
        }
    }
    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float partial) {
        g.pose().pushPose();double factor=minecraft.getWindow().getGuiScale();g.pose().scale((float)(1/factor),(float)(1/factor),1);
        int sw=minecraft.getWindow().getWidth(),sh=minecraft.getWindow().getHeight();
        g.fill(0,0,sw,sh,0xFF0A1119);
        String[] titles={"装備と充電器","ディスクセル Lv.1–3","ディスクセル Lv.4–6","持ち運べる音楽：PartyBox","ホーンの音質：SBL メガホン30"};
        text(g,"CREATE: MEDIA SYSTEMS  /  RECIPE GUIDE",24,18,1.4f,0xFF66DDE5);
        text(g,titles[page],24,44,2.5f,0xFFF0F7F9);text(g,(page+1)+" / 5",sw-92,47,2,0xFF9EB4C1);
        int start=switch(page){case 0->0;case 1->2;case 2->5;case 3->8;default->10;};int count=page==1||page==2?3:2;
        int gap=14,w=(sw-48-gap*(count-1))/count;
        for(int n=0;n<count;n++)card(g,recipes.get(start+n),24+n*(w+gap),93,w,sh-167);
        String note=page==2?"Lv.6のみ Create Plant 導入時に追加。A–E は未導入素材の記号（下の素材IDを参照）。":
                page==1?"新規セルはエネルギー0。Lv.2以降の強化では原本ディスクと残りFEを引き継ぎます。":"装備の全12レシピ。既存のスピーカー等は素材として使用します。";
        text(g,note,24,sh-53,1.5f,0xFF9EB4C1);
        text(g,"実際のレシピ定義とゲーム内アイテム描画から作成 / 1.6.0",24,sh-28,1.2f,0xFF657E8B);
        g.pose().popPose();
    }
    @Override public boolean isPauseScreen(){return false;}
}
