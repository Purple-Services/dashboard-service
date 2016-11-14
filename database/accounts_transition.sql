/* create accounts table */
DROP TABLE IF EXISTS `accounts`;

CREATE TABLE `accounts` (
  `id` varchar(255) NOT NULL,
  `name` varchar(255) DEFAULT 'Usually a business name',
  PRIMARY KEY (`id`)
);

/* accounts values */
INSERT INTO `accounts` VALUES ('w0RK0R6EcGYEzMTeWi5B','Jim Falk Lexus'),
       	    	       	      ('RpAiP52Qj26D3Vtc1650','Snow LA'),
			      ('PNwH18qWiIUvtCUkrBr1','AA Global'),
			      ('Y6ODz7roZ5haZpJ9aIKy','Pacific BMW'),
			      ('RlkL7Gp6S5IKFr6tcBSn','SKURT'),
			      ('rvLLOMnh8mqD08fmrFou','Bemus'),
			      ('BmFyfnvXpDaVcGvOrsng','Mauzy');

/* create account_children table */
DROP TABLE IF EXISTS `account_children`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `account_children` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `account_id` varchar(255) NOT NULL,
  `user_id` varchar(255) NOT NULL,
  PRIMARY KEY (`id`)
);

/* account_children values */
INSERT INTO `account_children` (`account_id`,`user_id`)
SELECT `account_manager_id`,`id` FROM `users` WHERE `account_manager_id` != '';

/* remove account_manager_id from users */
ALTER TABLE `users` DROP COLUMN `account_manager_id`;

/* create fleet_locations table */
DROP TABLE IF EXISTS `fleet_locations`;
CREATE TABLE `fleet_locations` (
  `id` varchar(255) NOT NULL,
  `account_id` varchar(255) NOT NULL,
  `name` varchar(255) NOT NULL,
  `address_zip` varchar(255) NOT NULL,
  PRIMARY KEY (`id`)
);

/* fleet_locations values */
INSERT INTO `fleet_locations`
VALUES ('UxE0goGbIOqQ3cQkSVG8','w0RK0R6EcGYEzMTeWi5B','Jim Falk Lexus Storage Facility','90067'),
       ('AdxtRqHRt9nYWIZSNias','rvLLOMnh8mqD08fmrFou','Bemus - Santa Ana','92705'),
       ('AoyBLki3EUlEXL4DKt32','RpAiP52Qj26D3Vtc1650','Snow LA - Stadium Way','90012'),
       ('jnK8887Ggfij9997ei34','PNwH18qWiIUvtCUkrBr1','AA Global','90011'),
       ('MaE7gvKkIOqQ3dwkSHd3','Y6ODz7roZ5haZpJ9aIKy','Pacific BMW','91204'),
       ('mmZXfMuI90hKRuyNoisjX9','RlkL7Gp6S5IKFr6tcBSn','SKURT - DTLA','90015'),
       ('QE0pLyG34z7jt0rCcB6LcB','RlkL7Gp6S5IKFr6tcBSn','SKURT - Hollywood','90068');
       
/* modify fleet_deliveries table */

-- UPDATE fleet_deliveries CASE fleet_deliveries.account_id WHEN SET fleet_deliveries.account_id = fleet_locations.id;

ALTER TABLE `fleet_deliveries`
CHANGE COLUMN `account_id` `fleet_location_id` varchar(255) NOT NULL;
ALTER TABLE `fleet_deliveries` ADD `service_fee` int(11) NOT NULL DEFAULT 0;
ALTER TABLE `fleet_deliveries` ADD `total_price` int(11) NOT NULL DEFAULT 0;

UPDATE `fleet_deliveries` SET `total_price` = CEIL(`gas_price` * `gallons`);
