package io.layertrace.core

import io.layertrace.core.idml.IdmlPackage
import io.layertrace.core.idml.IdmlTemplate

/** Hand-written IDML package exercising spreads, masters, threads, styles, images and unknown content. */
object Fixtures {
    private const val NS = IdmlTemplate.NS
    private const val H = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""

    // 1x1 transparent PNG
    const val PNG_B64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="

    val designmap = """$H
<?aid style="50" type="document" readerVersion="6.0" featureSet="257" product="16.0(32)" ?>
<Document xmlns:idPkg="$NS" DOMVersion="16.0" Self="d" StoryList="u200 u300 ue1" Name="Fixture.indd" ZeroPoint="0 0" ActiveLayer="ub3" CustomThing="keep-me">
	<idPkg:Graphic src="Resources/Graphic.xml"/>
	<idPkg:Fonts src="Resources/Fonts.xml"/>
	<idPkg:Styles src="Resources/Styles.xml"/>
	<idPkg:Preferences src="Resources/Preferences.xml"/>
	<Layer Self="ub3" Name="Text" Visible="true" Locked="false" IgnoreWrap="false" ShowGuides="true" LockGuides="false" UI="true" Expendable="true" Printable="true">
		<Properties><LayerColor type="enumeration">Red</LayerColor></Properties>
	</Layer>
	<Layer Self="ub4" Name="Background" Visible="true" Locked="false" Printable="true"/>
	<idPkg:MasterSpread src="MasterSpreads/MasterSpread_ud6.xml"/>
	<idPkg:Spread src="Spreads/Spread_ud3.xml"/>
	<idPkg:Spread src="Spreads/Spread_ud4.xml"/>
	<Section Self="uc8" Length="3" Name="" PageStart="ud8" SectionPrefix="" Marker=""/>
	<idPkg:BackingStory src="XML/BackingStory.xml"/>
	<idPkg:Story src="Stories/Story_u200.xml"/>
	<idPkg:Story src="Stories/Story_u300.xml"/>
	<ColorGroup Self="ColorGroup/[Root Color Group]" Name="[Root Color Group]" IsRootColorGroup="true">
		<ColorGroupSwatch Self="u18ColorGroupSwatch0" SwatchItemRef="Swatch/None"/>
		<ColorGroupSwatch Self="u18ColorGroupSwatch1" SwatchItemRef="Color/u90"/>
	</ColorGroup>
</Document>
"""

    val graphic = """$H
<idPkg:Graphic xmlns:idPkg="$NS" DOMVersion="16.0">
	<Color Self="Color/Black" Model="Process" Space="CMYK" ColorValue="0 0 0 100" Name="Black"/>
	<Color Self="Color/Paper" Model="Process" Space="CMYK" ColorValue="0 0 0 0" Name="Paper"/>
	<Color Self="Color/Registration" Model="Registration" Space="CMYK" ColorValue="100 100 100 100" Name="Registration"/>
	<Color Self="Color/u90" Model="Process" Space="RGB" ColorValue="255 0 0" Name="Red RGB"/>
	<Swatch Self="Swatch/None" Name="None"/>
	<Tint Self="Tint/u91" BaseColor="Color/u90" TintValue="50" Name="${'$'}ID/"/>
	<StrokeStyle Self="StrokeStyle/${'$'}ID/Solid" Name="${'$'}ID/Solid"/>
</idPkg:Graphic>
"""

    val styles = """$H
<idPkg:Styles xmlns:idPkg="$NS" DOMVersion="16.0">
	<RootCharacterStyleGroup Self="u79">
		<CharacterStyle Self="CharacterStyle/${'$'}ID/[No character style]" Name="${'$'}ID/[No character style]"/>
		<CharacterStyle Self="CharacterStyle/Emphasis" Name="Emphasis" FontStyle="Italic"/>
	</RootCharacterStyleGroup>
	<RootParagraphStyleGroup Self="u78">
		<ParagraphStyle Self="ParagraphStyle/${'$'}ID/[No paragraph style]" Name="${'$'}ID/[No paragraph style]" PointSize="12">
			<Properties><AppliedFont type="string">Minion Pro</AppliedFont><Leading type="enumeration">Auto</Leading></Properties>
		</ParagraphStyle>
		<ParagraphStyle Self="ParagraphStyle/${'$'}ID/NormalParagraphStyle" Name="${'$'}ID/NormalParagraphStyle">
			<Properties><BasedOn type="string">${'$'}ID/[No paragraph style]</BasedOn></Properties>
		</ParagraphStyle>
		<ParagraphStyleGroup Self="u80" Name="Headings">
			<ParagraphStyle Self="ParagraphStyle/Headings%3aH1" Name="Headings:H1" PointSize="24" Justification="CenterAlign" HyphenationZone="36">
				<Properties><BasedOn type="object">ParagraphStyle/${'$'}ID/NormalParagraphStyle</BasedOn><AppliedFont type="string">Helvetica Neue</AppliedFont><Leading type="unit">28</Leading></Properties>
			</ParagraphStyle>
		</ParagraphStyleGroup>
	</RootParagraphStyleGroup>
	<RootObjectStyleGroup Self="u9e"><ObjectStyle Self="ObjectStyle/${'$'}ID/[None]" Name="${'$'}ID/[None]"/></RootObjectStyleGroup>
</idPkg:Styles>
"""

    val prefs = """$H
<idPkg:Preferences xmlns:idPkg="$NS" DOMVersion="16.0">
	<DocumentPreference PageHeight="792" PageWidth="612" PagesPerDocument="3" FacingPages="true" DocumentBleedTopOffset="9" DocumentBleedBottomOffset="9" DocumentBleedInsideOrLeftOffset="9" DocumentBleedOutsideOrRightOffset="9"/>
	<MarginPreference ColumnCount="2" ColumnGutter="12" Top="36" Bottom="36" Left="36" Right="36"/>
</idPkg:Preferences>
"""

    val master = """$H
<idPkg:MasterSpread xmlns:idPkg="$NS" DOMVersion="16.0">
	<MasterSpread Self="ud6" ItemTransform="1 0 0 1 0 0" NamePrefix="A" BaseName="Master" Name="A-Master" PageCount="2">
		<Page Self="ud7" Name="A" GeometricBounds="0 0 792 612" ItemTransform="1 0 0 1 -612 -396" AppliedMaster="n"/>
		<Page Self="ud9" Name="A" GeometricBounds="0 0 792 612" ItemTransform="1 0 0 1 0 -396" AppliedMaster="n"/>
		<Rectangle Self="u100" ItemLayer="ub4" FillColor="Color/u90" StrokeColor="Swatch/None" ItemTransform="1 0 0 1 0 0">
			<Properties><PathGeometry><GeometryPathType PathOpen="false"><PathPointArray>
				<PathPointType Anchor="20 -380" LeftDirection="20 -380" RightDirection="20 -380"/>
				<PathPointType Anchor="20 -360" LeftDirection="20 -360" RightDirection="20 -360"/>
				<PathPointType Anchor="592 -360" LeftDirection="592 -360" RightDirection="592 -360"/>
				<PathPointType Anchor="592 -380" LeftDirection="592 -380" RightDirection="592 -380"/>
			</PathPointArray></GeometryPathType></PathGeometry></Properties>
		</Rectangle>
	</MasterSpread>
</idPkg:MasterSpread>
"""

    fun rectPath(l: Int, t: Int, r: Int, b: Int) = """<Properties><PathGeometry><GeometryPathType PathOpen="false"><PathPointArray>
				<PathPointType Anchor="$l $t" LeftDirection="$l $t" RightDirection="$l $t"/>
				<PathPointType Anchor="$l $b" LeftDirection="$l $b" RightDirection="$l $b"/>
				<PathPointType Anchor="$r $b" LeftDirection="$r $b" RightDirection="$r $b"/>
				<PathPointType Anchor="$r $t" LeftDirection="$r $t" RightDirection="$r $t"/>
			</PathPointArray></GeometryPathType></PathGeometry></Properties>"""

    val spread1 = """$H
<idPkg:Spread xmlns:idPkg="$NS" DOMVersion="16.0">
	<Spread Self="ud3" PageCount="1" BindingLocation="0" ItemTransform="1 0 0 1 0 0">
		<FlattenerPreference LineArtAndTextResolution="300"/>
		<Page Self="ud8" Name="1" AppliedMaster="ud6" GeometricBounds="0 0 792 612" ItemTransform="1 0 0 1 0 -396">
			<MarginPreference ColumnCount="2" ColumnGutter="12" Top="36" Bottom="36" Left="36" Right="36"/>
		</Page>
		<TextFrame Self="u210" ParentStory="u200" PreviousTextFrame="n" NextTextFrame="u211" ItemLayer="ub3" ItemTransform="1 0 0 1 0 0" ContentType="TextType" FillColor="Swatch/None" StrokeColor="Swatch/None">
			${rectPath(36, -360, 576, -300)}
			<TextFramePreference TextColumnCount="1" TextColumnGutter="12" AutoSizingType="Off"/>
			<TextWrapPreference TextWrapMode="None"/>
		</TextFrame>
		<Rectangle Self="u220" ItemLayer="ub4" ItemTransform="1 0 0 1 100 0" FillColor="Tint/u91" StrokeColor="Color/Black" StrokeWeight="2" TopLeftCornerOption="RoundedCorner" TopLeftCornerRadius="6">
			${rectPath(0, -200, 200, -50)}
			<TransparencySetting><BlendingSetting Opacity="50" BlendMode="Multiply"/></TransparencySetting>
			<Image Self="u221" ItemTransform="1 0 0 1 0 -200">
				<Properties><GraphicBounds Left="0" Top="0" Right="1" Bottom="1"/><Contents><![CDATA[$PNG_B64]]></Contents></Properties>
				<Link Self="u222" LinkResourceURI="file:/Users/me/pic.png" StoredState="Embedded"/>
			</Image>
		</Rectangle>
		<Group Self="u230" ItemLayer="ub3" ItemTransform="1 0 0 1 10 20">
			<Oval Self="u231" ItemLayer="ub3" ItemTransform="1 0 0 1 0 0" FillColor="Color/Black">${rectPath(0, 0, 50, 50)}</Oval>
			<GraphicLine Self="u232" ItemLayer="ub3" ItemTransform="1 0 0 1 0 0" StrokeColor="Color/Black" StrokeWeight="1">
				<Properties><PathGeometry><GeometryPathType PathOpen="true"><PathPointArray>
					<PathPointType Anchor="0 60" LeftDirection="0 60" RightDirection="0 60"/>
					<PathPointType Anchor="50 60" LeftDirection="50 60" RightDirection="50 60"/>
				</PathPointArray></GeometryPathType></PathGeometry></Properties>
			</GraphicLine>
		</Group>
		<Button Self="u240" ItemLayer="ub3" ItemTransform="1 0 0 1 0 0" Name="MyButton">
			<State Self="u241"><Rectangle Self="u242" ItemTransform="1 0 0 1 0 0"/></State>
		</Button>
		<Guide Self="u250" Orientation="Vertical" Location="306" PageIndex="0" ItemLayer="ub3"/>
	</Spread>
</idPkg:Spread>
"""

    val spread2 = """$H
<idPkg:Spread xmlns:idPkg="$NS" DOMVersion="16.0">
	<Spread Self="ud4" PageCount="2" BindingLocation="1" ItemTransform="1 0 0 1 0 830">
		<Page Self="uda" Name="2" AppliedMaster="ud6" GeometricBounds="0 0 792 612" ItemTransform="1 0 0 1 -612 -396"/>
		<Page Self="udb" Name="3" AppliedMaster="n" GeometricBounds="0 0 792 612" ItemTransform="1 0 0 1 0 -396"/>
		<TextFrame Self="u211" ParentStory="u200" PreviousTextFrame="u210" NextTextFrame="n" ItemLayer="ub3" ItemTransform="1 0 0 1 0 0">
			${rectPath(-576, -360, -36, 360)}
			<TextFramePreference TextColumnCount="2" TextColumnGutter="18"><Properties><InsetSpacing type="list"><ListItem type="unit">4</ListItem><ListItem type="unit">4</ListItem><ListItem type="unit">4</ListItem><ListItem type="unit">4</ListItem></InsetSpacing></Properties></TextFramePreference>
		</TextFrame>
		<TextFrame Self="u310" ParentStory="u300" PreviousTextFrame="n" NextTextFrame="n" ItemLayer="ub3" ItemTransform="0.7071067811865476 0.7071067811865475 -0.7071067811865475 0.7071067811865476 300 0">
			${rectPath(0, 0, 200, 100)}
		</TextFrame>
	</Spread>
</idPkg:Spread>
"""

    val story200 = """$H
<idPkg:Story xmlns:idPkg="$NS" DOMVersion="16.0">
	<Story Self="u200" AppliedTOCStyle="n" TrackChanges="false" StoryTitle="${'$'}ID/" AppliedNamedGrid="n">
		<StoryPreference OpticalMarginAlignment="false" FrameType="TextFrameType"/>
		<InCopyExportOption IncludeGraphicProxies="true" IncludeAllResources="false"/>
		<ParagraphStyleRange AppliedParagraphStyle="ParagraphStyle/Headings%3aH1">
			<CharacterStyleRange AppliedCharacterStyle="CharacterStyle/${'$'}ID/[No character style]">
				<Content>Hello &amp; welcome</Content>
				<Br/>
			</CharacterStyleRange>
		</ParagraphStyleRange>
		<ParagraphStyleRange AppliedParagraphStyle="ParagraphStyle/${'$'}ID/NormalParagraphStyle" Hyphenation="false">
			<CharacterStyleRange AppliedCharacterStyle="CharacterStyle/${'$'}ID/[No character style]" PointSize="10" Ligatures="false">
				<Properties><Leading type="unit">13</Leading><AppliedLanguage type="object">${'$'}ID/English: UK</AppliedLanguage></Properties>
				<Content>Body text with </Content>
			</CharacterStyleRange>
			<CharacterStyleRange AppliedCharacterStyle="CharacterStyle/Emphasis">
				<Content>emphasis</Content>
			</CharacterStyleRange>
			<CharacterStyleRange AppliedCharacterStyle="CharacterStyle/${'$'}ID/[No character style]">
				<Content> and page <?ACE 18?> number.</Content>
				<Br/>
				<Table Self="u400" BodyRowCount="1" ColumnCount="1"><Row Self="u400i0" Name="0"/></Table>
				<Content>After table.</Content>
			</CharacterStyleRange>
		</ParagraphStyleRange>
	</Story>
</idPkg:Story>
"""

    val story300 = """$H
<idPkg:Story xmlns:idPkg="$NS" DOMVersion="16.0">
	<Story Self="u300">
		<ParagraphStyleRange AppliedParagraphStyle="ParagraphStyle/${'$'}ID/NormalParagraphStyle">
			<CharacterStyleRange AppliedCharacterStyle="CharacterStyle/${'$'}ID/[No character style]"><Content>Rotated</Content></CharacterStyleRange>
		</ParagraphStyleRange>
	</Story>
</idPkg:Story>
"""

    fun entries(): LinkedHashMap<String, ByteArray> = linkedMapOf(
        "mimetype" to IdmlPackage.MIMETYPE.toByteArray(),
        "META-INF/container.xml" to IdmlTemplate.container.toByteArray(),
        "META-INF/metadata.xml" to "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>".toByteArray(),
        "designmap.xml" to designmap.toByteArray(),
        "Resources/Graphic.xml" to graphic.toByteArray(),
        "Resources/Fonts.xml" to IdmlTemplate.fonts.toByteArray(),
        "Resources/Styles.xml" to styles.toByteArray(),
        "Resources/Preferences.xml" to prefs.toByteArray(),
        "MasterSpreads/MasterSpread_ud6.xml" to master.toByteArray(),
        "Spreads/Spread_ud3.xml" to spread1.toByteArray(),
        "Spreads/Spread_ud4.xml" to spread2.toByteArray(),
        "Stories/Story_u200.xml" to story200.toByteArray(),
        "Stories/Story_u300.xml" to story300.toByteArray(),
        "XML/BackingStory.xml" to IdmlTemplate.backingStory.toByteArray(),
    )

    fun idml(): ByteArray = IdmlPackage.zip(entries())
}
