# Appendix E: Persistence SPI Interfaces (part 2/2)

127. It is not expected that a database foreign key be defined for the OneToOne mapping, as the OneToOne relationship may be defined as “optional=true”.

128. The derived id mechanisms described in Section 2.4.2.1 are now to be preferred over PrimaryKeyJoinColumn for the OneToOne mapping case.

129. Note that the derived identity mechanisms described in Section 2.4.2.1 is now preferred to the use of PrimaryKeyJoinColumn for this case.

130. When a joined inheritance strategy is used, the Table annotation is used to specify a primary table for the subclass-specific state if the default is not used.

131. If the element collection is a Map, this applies to the map value.

 3.2

Last updated 2024-04-10 08:48:35 UTC
