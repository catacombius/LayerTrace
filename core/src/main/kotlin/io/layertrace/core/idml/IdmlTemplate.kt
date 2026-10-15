package io.layertrace.core.idml

import io.layertrace.core.model.SourcePackage
import io.layertrace.core.xml.XmlParser

/**
 * The minimal set of package files InDesign needs to open an IDML document.
 * New documents start from this and [IdmlWriter] merges the model into it.
 */
object IdmlTemplate {
    const val DOM_VERSION = "8.0"
    const val NS = "http://ns.adobe.com/AdobeInDesign/idml/1.0/packaging"

    private const val HEADER = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""

    val container = """$HEADER
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
	<rootfiles>
		<rootfile full-path="designmap.xml" media-type="text/xml"/>
	</rootfiles>
</container>
"""

    val designmap = """$HEADER
<?aid style="50" type="document" readerVersion="6.0" featureSet="257" product="8.0(370)" ?>
<Document xmlns:idPkg="$NS" DOMVersion="$DOM_VERSION" Self="d" StoryList="" Name="Untitled" ZeroPoint="0 0" ActiveLayer="ub3" CMYKProfile="Coated FOGRA39 (ISO 12647-2:2004)" RGBProfile="sRGB IEC61966-2.1" SolidColorIntent="UseColorSettings" AfterBlendingIntent="UseColorSettings" DefaultImageIntent="UseColorSettings" RGBPolicy="PreserveEmbeddedProfiles" CMYKPolicy="CombinationOfPreserveAndSafeCmyk" AccurateLABSpots="false">
	<Language Self="Language/${'$'}ID/English%3a USA" Name="${'$'}ID/English: USA" SingleQuotes="‘’" DoubleQuotes="“”" PrimaryLanguageName="${'$'}ID/English" SublanguageName="${'$'}ID/USA" Id="269" HyphenationVendor="Hunspell" SpellingVendor="Hunspell"/>
	<idPkg:Graphic src="Resources/Graphic.xml"/>
	<idPkg:Fonts src="Resources/Fonts.xml"/>
	<idPkg:Styles src="Resources/Styles.xml"/>
	<idPkg:Preferences src="Resources/Preferences.xml"/>
	<Layer Self="ub3" Name="Layer 1" Visible="true" Locked="false" IgnoreWrap="false" ShowGuides="true" LockGuides="false" UI="true" Expendable="true" Printable="true">
		<Properties>
			<LayerColor type="enumeration">LightBlue</LayerColor>
		</Properties>
	</Layer>
	<Section Self="uc8" Length="1" Name="" ContinueNumbering="true" IncludeSectionPrefix="false" PageNumberStyle="Arabic" PageStart="ud8" SectionPrefix="" Marker="" AlternateLayoutLength="1" AlternateLayout="">
		<Properties>
			<PageNumberStart type="long">1</PageNumberStart>
		</Properties>
	</Section>
	<idPkg:BackingStory src="XML/BackingStory.xml"/>
	<idPkg:Tags src="XML/Tags.xml"/>
</Document>
"""

    val graphic = """$HEADER
<idPkg:Graphic xmlns:idPkg="$NS" DOMVersion="$DOM_VERSION">
	<Color Self="Color/Black" Model="Process" Space="CMYK" ColorValue="0 0 0 100" ColorOverride="Specialblack" AlternateSpace="NoAlternateColor" AlternateColorValue="" Name="Black" ColorEditable="false" ColorRemovable="false" Visible="true" SwatchCreatorID="7937"/>
	<Color Self="Color/Paper" Model="Process" Space="CMYK" ColorValue="0 0 0 0" ColorOverride="Specialpaper" AlternateSpace="NoAlternateColor" AlternateColorValue="" Name="Paper" ColorEditable="true" ColorRemovable="false" Visible="true" SwatchCreatorID="7937"/>
	<Color Self="Color/Registration" Model="Registration" Space="CMYK" ColorValue="100 100 100 100" ColorOverride="Specialregistration" AlternateSpace="NoAlternateColor" AlternateColorValue="" Name="Registration" ColorEditable="false" ColorRemovable="false" Visible="true" SwatchCreatorID="7937"/>
	<Color Self="Color/C=100 M=0 Y=0 K=0" Model="Process" Space="CMYK" ColorValue="100 0 0 0" ColorOverride="Normal" AlternateSpace="NoAlternateColor" AlternateColorValue="" Name="C=100 M=0 Y=0 K=0" ColorEditable="true" ColorRemovable="true" Visible="true" SwatchCreatorID="7937"/>
	<Color Self="Color/C=0 M=100 Y=0 K=0" Model="Process" Space="CMYK" ColorValue="0 100 0 0" ColorOverride="Normal" AlternateSpace="NoAlternateColor" AlternateColorValue="" Name="C=0 M=100 Y=0 K=0" ColorEditable="true" ColorRemovable="true" Visible="true" SwatchCreatorID="7937"/>
	<Color Self="Color/C=0 M=0 Y=100 K=0" Model="Process" Space="CMYK" ColorValue="0 0 100 0" ColorOverride="Normal" AlternateSpace="NoAlternateColor" AlternateColorValue="" Name="C=0 M=0 Y=100 K=0" ColorEditable="true" ColorRemovable="true" Visible="true" SwatchCreatorID="7937"/>
	<Color Self="Color/C=15 M=100 Y=100 K=0" Model="Process" Space="CMYK" ColorValue="15 100 100 0" ColorOverride="Normal" AlternateSpace="NoAlternateColor" AlternateColorValue="" Name="C=15 M=100 Y=100 K=0" ColorEditable="true" ColorRemovable="true" Visible="true" SwatchCreatorID="7937"/>
	<Color Self="Color/C=75 M=5 Y=100 K=0" Model="Process" Space="CMYK" ColorValue="75 5 100 0" ColorOverride="Normal" AlternateSpace="NoAlternateColor" AlternateColorValue="" Name="C=75 M=5 Y=100 K=0" ColorEditable="true" ColorRemovable="true" Visible="true" SwatchCreatorID="7937"/>
	<Color Self="Color/C=100 M=90 Y=10 K=0" Model="Process" Space="CMYK" ColorValue="100 90 10 0" ColorOverride="Normal" AlternateSpace="NoAlternateColor" AlternateColorValue="" Name="C=100 M=90 Y=10 K=0" ColorEditable="true" ColorRemovable="true" Visible="true" SwatchCreatorID="7937"/>
	<Ink Self="Ink/${'$'}ID/Process Cyan" Name="${'$'}ID/Process Cyan" Angle="75" ConvertToProcess="false" Frequency="70" NeutralDensity="0.61" PrintInk="true" TrapOrder="1" InkType="Normal"/>
	<Ink Self="Ink/${'$'}ID/Process Magenta" Name="${'$'}ID/Process Magenta" Angle="15" ConvertToProcess="false" Frequency="70" NeutralDensity="0.76" PrintInk="true" TrapOrder="2" InkType="Normal"/>
	<Ink Self="Ink/${'$'}ID/Process Yellow" Name="${'$'}ID/Process Yellow" Angle="0" ConvertToProcess="false" Frequency="70" NeutralDensity="0.16" PrintInk="true" TrapOrder="3" InkType="Normal"/>
	<Ink Self="Ink/${'$'}ID/Process Black" Name="${'$'}ID/Process Black" Angle="45" ConvertToProcess="false" Frequency="70" NeutralDensity="1.7" PrintInk="true" TrapOrder="4" InkType="Normal"/>
	<Swatch Self="Swatch/None" Name="None" ColorEditable="false" ColorRemovable="false" Visible="true" SwatchCreatorID="7937"/>
	<StrokeStyle Self="StrokeStyle/${'$'}ID/Solid" Name="${'$'}ID/Solid"/>
</idPkg:Graphic>
"""

    val fonts = """$HEADER
<idPkg:Fonts xmlns:idPkg="$NS" DOMVersion="$DOM_VERSION">
</idPkg:Fonts>
"""

    val styles = """$HEADER
<idPkg:Styles xmlns:idPkg="$NS" DOMVersion="$DOM_VERSION">
	<RootCharacterStyleGroup Self="u79">
		<CharacterStyle Self="CharacterStyle/${'$'}ID/[No character style]" Imported="false" Name="${'$'}ID/[No character style]"/>
	</RootCharacterStyleGroup>
	<RootParagraphStyleGroup Self="u78">
		<ParagraphStyle Self="ParagraphStyle/${'$'}ID/[No paragraph style]" Name="${'$'}ID/[No paragraph style]" Imported="false" NextParagraphStyle="ParagraphStyle/${'$'}ID/[No paragraph style]" FillColor="Color/Black" FontStyle="Regular" PointSize="12" Justification="LeftAlign" LeftIndent="0" RightIndent="0" FirstLineIndent="0" SpaceBefore="0" SpaceAfter="0" Tracking="0" Capitalization="Normal" Underline="false" StrikeThru="false" BaselineShift="0">
			<Properties>
				<AppliedFont type="string">Minion Pro</AppliedFont>
				<Leading type="enumeration">Auto</Leading>
			</Properties>
		</ParagraphStyle>
		<ParagraphStyle Self="ParagraphStyle/${'$'}ID/NormalParagraphStyle" Name="${'$'}ID/NormalParagraphStyle" Imported="false" NextParagraphStyle="ParagraphStyle/${'$'}ID/NormalParagraphStyle">
			<Properties>
				<BasedOn type="string">${'$'}ID/[No paragraph style]</BasedOn>
			</Properties>
		</ParagraphStyle>
	</RootParagraphStyleGroup>
	<TOCStyle Self="TOCStyle/${'$'}ID/DefaultTOCStyleName" Title="Contents" Name="${'$'}ID/DefaultTOCStyleName" TitleStyle="ParagraphStyle/${'$'}ID/[No paragraph style]"/>
	<RootCellStyleGroup Self="u93">
		<CellStyle Self="CellStyle/${'$'}ID/[None]" AppliedParagraphStyle="ParagraphStyle/${'$'}ID/[No paragraph style]" Name="${'$'}ID/[None]"/>
	</RootCellStyleGroup>
	<RootTableStyleGroup Self="u95">
		<TableStyle Self="TableStyle/${'$'}ID/[No table style]" Name="${'$'}ID/[No table style]"/>
		<TableStyle Self="TableStyle/${'$'}ID/[Basic Table]" Name="${'$'}ID/[Basic Table]"/>
	</RootTableStyleGroup>
	<RootObjectStyleGroup Self="u9e">
		<ObjectStyle Self="ObjectStyle/${'$'}ID/[None]" Name="${'$'}ID/[None]" AppliedParagraphStyle="ParagraphStyle/${'$'}ID/[No paragraph style]" FillColor="Swatch/None" StrokeWeight="0" StrokeColor="Swatch/None"/>
		<ObjectStyle Self="ObjectStyle/${'$'}ID/[Normal Graphics Frame]" Name="${'$'}ID/[Normal Graphics Frame]" AppliedParagraphStyle="ParagraphStyle/${'$'}ID/NormalParagraphStyle" FillColor="Swatch/None" StrokeWeight="1" StrokeColor="Swatch/None">
			<Properties>
				<BasedOn type="object">ObjectStyle/${'$'}ID/[None]</BasedOn>
			</Properties>
		</ObjectStyle>
		<ObjectStyle Self="ObjectStyle/${'$'}ID/[Normal Text Frame]" Name="${'$'}ID/[Normal Text Frame]" AppliedParagraphStyle="ParagraphStyle/${'$'}ID/NormalParagraphStyle" FillColor="Swatch/None" StrokeWeight="1" StrokeColor="Swatch/None">
			<Properties>
				<BasedOn type="object">ObjectStyle/${'$'}ID/[None]</BasedOn>
			</Properties>
		</ObjectStyle>
		<ObjectStyle Self="ObjectStyle/${'$'}ID/[Normal Grid]" Name="${'$'}ID/[Normal Grid]" AppliedParagraphStyle="ParagraphStyle/${'$'}ID/NormalParagraphStyle" FillColor="Swatch/None" StrokeWeight="1" StrokeColor="Swatch/None">
			<Properties>
				<BasedOn type="object">ObjectStyle/${'$'}ID/[None]</BasedOn>
			</Properties>
		</ObjectStyle>
	</RootObjectStyleGroup>
</idPkg:Styles>
"""

    val preferences = """$HEADER
<idPkg:Preferences xmlns:idPkg="$NS" DOMVersion="$DOM_VERSION">
	<DocumentPreference PageHeight="841.8897637795276" PageWidth="595.2755905511812" PagesPerDocument="1" FacingPages="true" DocumentBleedTopOffset="0" DocumentBleedBottomOffset="0" DocumentBleedInsideOrLeftOffset="0" DocumentBleedOutsideOrRightOffset="0" DocumentBleedUniformSize="true" SlugTopOffset="0" SlugBottomOffset="0" SlugInsideOrLeftOffset="0" SlugRightOrOutsideOffset="0" DocumentSlugUniformSize="false" PreserveLayoutWhenShuffling="true" AllowPageShuffle="true" OverprintBlack="true" PageBinding="LeftToRight" ColumnDirection="Horizontal" ColumnGuideLocked="true" MasterTextFrame="false" SnippetImportUsesOriginalLocation="false" Intent="PrintIntent"/>
	<MarginPreference ColumnCount="1" ColumnGutter="12" Top="36" Bottom="36" Left="36" Right="36" ColumnDirection="Horizontal" ColumnsPositions="0 523.2755905511812"/>
	<ViewPreference HorizontalMeasurementUnits="Millimeters" VerticalMeasurementUnits="Millimeters" RulerOrigin="SpreadOrigin"/>
</idPkg:Preferences>
"""

    val backingStory = """$HEADER
<idPkg:BackingStory xmlns:idPkg="$NS" DOMVersion="$DOM_VERSION">
	<XmlStory Self="ue1" AppliedTOCStyle="n" TrackChanges="false" StoryTitle="${'$'}ID/" AppliedNamedGrid="n">
		<ParagraphStyleRange AppliedParagraphStyle="ParagraphStyle/${'$'}ID/NormalParagraphStyle">
			<CharacterStyleRange AppliedCharacterStyle="CharacterStyle/${'$'}ID/[No character style]"/>
		</ParagraphStyleRange>
	</XmlStory>
</idPkg:BackingStory>
"""

    val tags = """$HEADER
<idPkg:Tags xmlns:idPkg="$NS" DOMVersion="$DOM_VERSION">
	<XMLTag Self="XMLTag/Root" Name="Root">
		<Properties>
			<TagColor type="enumeration">LightBlue</TagColor>
		</Properties>
	</XMLTag>
</idPkg:Tags>
"""

    fun entries(): LinkedHashMap<String, ByteArray> = linkedMapOf(
        "mimetype" to IdmlPackage.MIMETYPE.toByteArray(),
        "META-INF/container.xml" to container.toByteArray(),
        "designmap.xml" to designmap.toByteArray(),
        "Resources/Graphic.xml" to graphic.toByteArray(),
        "Resources/Fonts.xml" to fonts.toByteArray(),
        "Resources/Styles.xml" to styles.toByteArray(),
        "Resources/Preferences.xml" to preferences.toByteArray(),
        "XML/BackingStory.xml" to backingStory.toByteArray(),
        "XML/Tags.xml" to tags.toByteArray(),
    )

    fun sourcePackage(): SourcePackage {
        val e = entries()
        return SourcePackage(e, XmlParser.parse(e["designmap.xml"]!!))
    }
}
